/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.collectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;

/**
 * The database operation and state counts (Operations Manual 5, "Database operation count metrics" and
 * "Database state count metrics", both global) as an administrator observes them through
 * {@code CALL dbms.queryJmx(...)} while databases are created, stopped, started and dropped through Cypher on the
 * system database. DozerDB does not accept the {@code WAIT} clause, so the tests poll {@code SHOW DATABASES}. The
 * server runs beside other tests' servers in the same JVM and therefore has its own metric prefix.
 */
class DatabaseCountMetricsIT {

    private static final String PREFIX = "dbcounts";
    private static final String OBJECT_NAME_PREFIX = PREFIX + ".metrics:name=" + PREFIX + ".dbms.db.";

    private static Neo4j neo4j;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithAnOpenFilter() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, PREFIX));
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    /** A database is created and then started, so {@code CREATE DATABASE} is one create and one start operation. */
    @Test
    void creatingADatabaseCountsOneCreateAndOneStart() {
        long created = count("operation.count.create");
        long started = count("operation.count.start");

        execute("CREATE DATABASE created");
        awaitStatus("created", Optional.of("online"));

        assertThat(count("operation.count.create")).as("create").isEqualTo(created + 1);
        assertThat(count("operation.count.start")).as("start").isEqualTo(started + 1);
    }

    /**
     * Stopping a database is one stop, starting it again one start and no create (Neo4j initialises it again, which
     * is not a create operation), and dropping it while online stops it first: one stop and one drop.
     */
    @Test
    void stoppingStartingAndDroppingAnOnlineDatabaseCountEachOperation() {
        execute("CREATE DATABASE cycled");
        awaitStatus("cycled", Optional.of("online"));
        Map<String, Long> before = operationCounts();

        execute("STOP DATABASE cycled");
        awaitStatus("cycled", Optional.of("offline"));
        assertThat(count("operation.count.stop")).as("stop after STOP").isEqualTo(before.get("stop") + 1);

        execute("START DATABASE cycled");
        awaitStatus("cycled", Optional.of("online"));
        assertThat(count("operation.count.start")).as("start after START").isEqualTo(before.get("start") + 1);
        assertThat(count("operation.count.create")).as("create after START").isEqualTo(before.get("create"));

        execute("DROP DATABASE cycled");
        awaitStatus("cycled", Optional.empty());
        assertThat(count("operation.count.stop"))
                .as("stop after DROP of an online database")
                .isEqualTo(before.get("stop") + 2);
        assertThat(count("operation.count.drop")).as("drop").isEqualTo(before.get("drop") + 1);
    }

    /**
     * A server started again on the same home initialises every existing database, and Neo4j announces each
     * initialisation with the same public event as a new database, but none is created: the create count of a
     * restarted server is zero. Starting a database is a start operation, counted when the metrics are already
     * running; which of the boot-time starts precede the metrics depends on Neo4j's boot order (at 5.26.32 the
     * default database initializer joins the global lifecycle before the extensions, so every boot-time start
     * precedes them), so the restarted server's start count is observed on a database started after the boot.
     */
    @Test
    void aServerStartedAgainCountsItsExistingDatabasesAsStartedButNotCreated(@TempDir Path home) {
        String prefix = "dbcountsboot";
        Consumer<Neo4jBuilder> settings =
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, prefix);
        try (Neo4j first = MetricsServers.startInHome(home, settings)) {
            GraphDatabaseService firstSystem = first.databaseManagementService().database("system");
            execute(firstSystem, "CREATE DATABASE existing");
            awaitStatus(firstSystem, "existing", Optional.of("online"));
        }

        try (Neo4j again = MetricsServers.startInHome(home, settings)) {
            GraphDatabaseService againSystem = again.databaseManagementService().database("system");
            awaitStatus(againSystem, "existing", Optional.of("online"));
            String counter = prefix + ".metrics:name=" + prefix + ".dbms.db.operation.count.";

            assertThat(JmxQueries.attribute(againSystem, counter + "create", "Count"))
                    .as("existing databases are not created again")
                    .isEqualTo(0L);
            execute(againSystem, "STOP DATABASE existing");
            awaitStatus(againSystem, "existing", Optional.of("offline"));
            long starts = (Long) JmxQueries.attribute(againSystem, counter + "start", "Count");
            execute(againSystem, "START DATABASE existing");
            awaitStatus(againSystem, "existing", Optional.of("online"));
            assertThat((Long) JmxQueries.attribute(againSystem, counter + "start", "Count"))
                    .as("a database started after the metrics is counted as started")
                    .isEqualTo(starts + 1);
        }
    }

    /** Dropping a database that is already stopped is one drop and no further stop. */
    @Test
    void droppingAStoppedDatabaseCountsOnlyADrop() {
        execute("CREATE DATABASE parked");
        awaitStatus("parked", Optional.of("online"));
        execute("STOP DATABASE parked");
        awaitStatus("parked", Optional.of("offline"));
        Map<String, Long> before = operationCounts();

        execute("DROP DATABASE parked");
        awaitStatus("parked", Optional.empty());

        assertThat(count("operation.count.drop")).as("drop").isEqualTo(before.get("drop") + 1);
        assertThat(count("operation.count.stop")).as("stop").isEqualTo(before.get("stop"));
    }

    /**
     * The state counts are snapshots of the databases the server hosts: hosted are the ones online, desired started
     * the ones requested online, and none has failed in a healthy server. A stopped database is still registered but
     * neither hosted nor desired started.
     */
    @Test
    void theStateCountsFollowTheDatabasesShowDatabasesLists() {
        execute("CREATE DATABASE stateful");
        awaitStatus("stateful", Optional.of("online"));
        assertThat(gauge("state.count.hosted")).as("hosted while online").isEqualTo(databasesWith("currentStatus"));
        assertThat(gauge("state.count.desired_started"))
                .as("desired_started while online")
                .isEqualTo(databasesWith("requestedStatus"));
        assertThat(gauge("state.count.failed")).as("failed").isZero();
        long hosted = gauge("state.count.hosted");
        long desiredStarted = gauge("state.count.desired_started");

        execute("STOP DATABASE stateful");
        awaitStatus("stateful", Optional.of("offline"));

        assertThat(gauge("state.count.hosted")).as("hosted after STOP").isEqualTo(hosted - 1);
        assertThat(gauge("state.count.desired_started"))
                .as("desired_started after STOP")
                .isEqualTo(desiredStarted - 1);
        assertThat(gauge("state.count.hosted")).isEqualTo(databasesWith("currentStatus"));
        assertThat(gauge("state.count.desired_started")).isEqualTo(databasesWith("requestedStatus"));
        assertThat(gauge("state.count.failed")).as("failed after STOP").isZero();
    }

    /**
     * A database whose store directory has been replaced by a regular file cannot start: {@code START DATABASE}
     * leaves it failed, which {@code SHOW DATABASES} reports as a status message. It then counts as failed and as
     * desired started (the operator asked for it) but not as hosted. Stopping it clears the failure, and once the
     * store is back it starts again. A failed start is not a database event, so the operation counters do not see it;
     * only the state counts do.
     */
    @Test
    void aDatabaseThatFailsToStartIsFailedAndDesiredStartedButNotHostedUntilItIsStoppedAndRepaired()
            throws IOException {
        execute("CREATE DATABASE broken");
        awaitStatus("broken", Optional.of("online"));
        execute("STOP DATABASE broken");
        awaitStatus("broken", Optional.of("offline"));
        long hosted = gauge("state.count.hosted");
        long desiredStarted = gauge("state.count.desired_started");
        Path store = databaseDirectory("broken");
        Path aside = store.resolveSibling("broken.aside");
        Files.move(store, aside);
        Files.createFile(store);
        try {
            execute("START DATABASE broken");
            Await.until(
                    "broken reports its failure", () -> !statusMessage("broken").isEmpty());

            assertThat(gauge("state.count.failed")).as("failed while broken").isEqualTo(1L);
            assertThat(gauge("state.count.hosted")).as("hosted while broken").isEqualTo(hosted);
            assertThat(gauge("state.count.desired_started"))
                    .as("desired_started while broken")
                    .isEqualTo(desiredStarted + 1)
                    .isEqualTo(databasesWith("requestedStatus"));

            execute("STOP DATABASE broken");
            Await.until("broken has no failure", () -> statusMessage("broken").isEmpty());
            assertThat(gauge("state.count.failed")).as("failed after STOP").isZero();
        } finally {
            Files.delete(store);
            Files.move(aside, store);
        }

        execute("START DATABASE broken");
        awaitStatus("broken", Optional.of("online"));
        assertThat(gauge("state.count.hosted")).as("hosted once repaired").isEqualTo(hosted + 1);
        assertThat(gauge("state.count.failed")).as("failed once repaired").isZero();

        execute("DROP DATABASE broken");
        awaitStatus("broken", Optional.empty());
    }

    private static Path databaseDirectory(String database) {
        return neo4j.config()
                .get(GraphDatabaseInternalSettings.databases_root_path)
                .resolve(database);
    }

    private static String statusMessage(String database) {
        try (Transaction tx = system.beginTx()) {
            return tx
                    .execute(
                            "SHOW DATABASES YIELD name, statusMessage WHERE name = $name RETURN statusMessage",
                            Map.of("name", database))
                    .stream()
                    .map(row -> (String) row.get("statusMessage"))
                    .findFirst()
                    .orElse("");
        }
    }

    /** The number of databases {@code SHOW DATABASES} lists with the status column {@code online}. */
    private static long databasesWith(String statusColumn) {
        try (Transaction tx = system.beginTx()) {
            return (Long) tx.execute("SHOW DATABASES YIELD name, " + statusColumn + " WHERE " + statusColumn
                            + " = 'online' RETURN count(DISTINCT name) AS databases")
                    .next()
                    .get("databases");
        }
    }

    /** The {@code Value} attribute of a state gauge. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of db." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** Every operation counter's count, keyed by operation. */
    private static Map<String, Long> operationCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String operation : List.of("create", "start", "stop", "drop", "failed", "recovered")) {
            counts.put(operation, count("operation.count." + operation));
        }
        return counts;
    }

    /** The {@code Count} attribute of an operation counter. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(system, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of db." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }

    private static void execute(String cypher) {
        execute(system, cypher);
    }

    private static void execute(GraphDatabaseService system, String cypher) {
        try (Transaction tx = system.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    /** Polls {@code SHOW DATABASES} until the database reports the status, or is gone when none is expected. */
    private static void awaitStatus(String database, Optional<String> status) {
        awaitStatus(system, database, status);
    }

    private static void awaitStatus(GraphDatabaseService system, String database, Optional<String> status) {
        Await.until(database + " has status " + status.orElse("<absent>"), () -> currentStatus(system, database)
                .equals(status));
    }

    private static Optional<String> currentStatus(GraphDatabaseService system, String database) {
        try (Transaction tx = system.beginTx()) {
            return tx
                    .execute(
                            "SHOW DATABASES YIELD name, currentStatus WHERE name = $name RETURN currentStatus",
                            Map.of("name", database))
                    .stream()
                    .map(row -> (String) row.get("currentStatus"))
                    .findFirst();
        }
    }
}
