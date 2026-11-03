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

import java.util.Map;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * The Bolt driver metrics (Operations Manual 5, "Bolt Driver metrics") as an administrator observes them:
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.bolt_driver.api.<metric>")}. A driver names the API it
 * used in a TELEMETRY message, which it sends only when the server offers telemetry
 * ({@code server.bolt.telemetry.enabled=true}); each of the Neo4j Java driver's four APIs is exercised once.
 *
 * <p>The driver sends api 0 for a managed transaction function, 1 for an unmanaged transaction, 2 for an auto-commit
 * (implicit) transaction and 3 for a driver-level execute; Neo4j's {@code TelemetryStateTransition} at 5.26.32
 * routes marker 0 to the implicit counter and marker 2 to the managed counter. DozerDB counts what Neo4j's Bolt
 * server fires, so a transaction function lands in {@code implicit_transaction_calls} and a {@code session.run} in
 * {@code managed_transaction_function_calls}; these tests pin that mapping so a Neo4j upgrade that changes it is
 * noticed (see the inventory rows' notes).
 */
class BoltDriverMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.dbms.bolt_driver.api.";
    private static final String MANAGED = "managed_transaction_function_calls";
    private static final String UNMANAGED = "unmanaged_transaction_calls";
    private static final String IMPLICIT = "implicit_transaction_calls";
    private static final String EXECUTE = "execute_calls";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithBoltTelemetryAndAnOpenFilter() {
        neo4j = MetricsServers.startWithBolt(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(BoltConnector.server_bolt_telemetry_enabled, true));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aManagedTransactionFunctionIsCountedWhereNeo4jRoutesTelemetryApi0TheImplicitCounter() {
        Map<String, Long> before = counts();

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("RETURN 1").consume());
        }

        assertOnlyIncremented(IMPLICIT, before);
    }

    @Test
    void anUnmanagedTransactionIsCounted() {
        Map<String, Long> before = counts();

        try (Driver driver = driver();
                Session session = driver.session();
                Transaction tx = session.beginTransaction()) {
            tx.run("RETURN 1").consume();
            tx.commit();
        }

        assertOnlyIncremented(UNMANAGED, before);
    }

    @Test
    void anAutoCommitTransactionIsCountedWhereNeo4jRoutesTelemetryApi2TheManagedCounter() {
        Map<String, Long> before = counts();

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
        }

        assertOnlyIncremented(MANAGED, before);
    }

    @Test
    void aDriverLevelExecuteIsCounted() {
        Map<String, Long> before = counts();

        try (Driver driver = driver()) {
            driver.executableQuery("RETURN 1").execute();
        }

        assertOnlyIncremented(EXECUTE, before);
    }

    private static void assertOnlyIncremented(String metric, Map<String, Long> before) {
        Await.until(metric + " is counted", () -> count(metric) == before.get(metric) + 1);
        before.forEach((other, count) -> {
            if (!other.equals(metric)) {
                assertThat(count(other)).as(other).isEqualTo(count);
            }
        });
    }

    private static Map<String, Long> counts() {
        return Map.of(
                MANAGED,
                count(MANAGED),
                UNMANAGED,
                count(UNMANAGED),
                IMPLICIT,
                count(IMPLICIT),
                EXECUTE,
                count(EXECUTE));
    }

    private static Driver driver() {
        return GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
    }

    /** The {@code Count} attribute of a driver counter, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(db, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of bolt_driver.api." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }
}
