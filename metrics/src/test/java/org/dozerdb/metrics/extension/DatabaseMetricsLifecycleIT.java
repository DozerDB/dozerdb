/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * Database metrics live and die with their database (Operations Manual 2026.10: "Database metrics are only
 * available during the lifetime of the database"): an administrator who creates a database sees its metrics in
 * the JMX domain once it is online, loses them when it is stopped, gets them back when it is started and loses
 * them when it is dropped. The system database has its own set like any other. DozerDB does not accept the
 * {@code WAIT} clause, so the tests poll {@code SHOW DATABASES} for the requested state.
 */
class DatabaseMetricsLifecycleIT {

    private static final String DATABASE = "lifecycle";
    private static final String STARTED = "transaction.started";

    private static Neo4j neo4j;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithAnOpenFilter() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")));
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void theSystemDatabaseAndTheDefaultDatabaseEachHaveTheirOwnTransactionMetrics() {
        assertThat(JmxQueries.names(system, "neo4j.metrics:name=neo4j.database.*." + STARTED))
                .contains(objectName("system", STARTED), objectName("neo4j", STARTED));
    }

    @Test
    void aDatabasesMetricsAppearWhenItStartsAndDisappearWhenItStopsOrIsDropped() {
        execute("CREATE DATABASE " + DATABASE);
        awaitStatus(Optional.of("online"));
        assertThat(metricsOf(DATABASE)).as("created and online").contains(objectName(DATABASE, STARTED));
        assertThat(startedTransactionsOf(DATABASE)).isGreaterThanOrEqualTo(0L);
        long started = startedTransactionsOf(DATABASE);
        try (Transaction tx =
                neo4j.databaseManagementService().database(DATABASE).beginTx()) {
            tx.createNode();
            tx.commit();
        }
        assertThat(startedTransactionsOf(DATABASE))
                .as("the new database's own transactions are counted")
                .isEqualTo(started + 1);

        execute("STOP DATABASE " + DATABASE);
        awaitStatus(Optional.of("offline"));
        assertThat(metricsOf(DATABASE)).as("stopped").isEmpty();

        execute("START DATABASE " + DATABASE);
        awaitStatus(Optional.of("online"));
        assertThat(metricsOf(DATABASE)).as("started again").contains(objectName(DATABASE, STARTED));
        long sizes = countOf(DATABASE, "transaction.tx_size_heap");
        try (Transaction tx =
                neo4j.databaseManagementService().database(DATABASE).beginTx()) {
            tx.createNode();
            tx.commit();
        }
        assertThat(countOf(DATABASE, "transaction.tx_size_heap"))
                .as("the restarted database's transaction sizes feed its new histogram")
                .isEqualTo(sizes + 1);

        execute("DROP DATABASE " + DATABASE);
        awaitStatus(Optional.empty());
        assertThat(metricsOf(DATABASE)).as("dropped").isEmpty();
    }

    private static String objectName(String database, String metric) {
        return "neo4j.metrics:name=neo4j.database." + database + "." + metric;
    }

    private static Set<String> metricsOf(String database) {
        return JmxQueries.names(system, "neo4j.metrics:name=neo4j.database." + database + ".*");
    }

    private static long startedTransactionsOf(String database) {
        return countOf(database, STARTED);
    }

    private static long countOf(String database, String metric) {
        Object count = JmxQueries.attribute(system, objectName(database, metric), "Count");
        assertThat(count).as("Count of " + database + " " + metric).isInstanceOf(Long.class);
        return (Long) count;
    }

    private static void execute(String cypher) {
        try (Transaction tx = system.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    /** Polls {@code SHOW DATABASES} until the database reports the status, or is gone when none is expected. */
    private static void awaitStatus(Optional<String> status) {
        Await.until(
                DATABASE + " has status " + status.orElse("<absent>"),
                () -> currentStatus().equals(status));
    }

    private static Optional<String> currentStatus() {
        try (Transaction tx = system.beginTx()) {
            return tx
                    .execute(
                            "SHOW DATABASES YIELD name, currentStatus WHERE name = $name RETURN currentStatus",
                            Map.of("name", DATABASE))
                    .stream()
                    .map(row -> (String) row.get("currentStatus"))
                    .findFirst();
        }
    }
}
