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

import java.util.List;
import java.util.Set;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The Neo4j memory pool metrics (Operations Manual 2026.10, "Global neo4j pools metrics" and "Database neo4j pools
 * metrics") as an administrator observes them: {@code neo4j.dbms.pool.<pool>.<field>} for every global pool and
 * {@code neo4j.database.<db>.pool.<pool>.<db>.<field>} for every pool of a database. {@code <pool>} is the pool's
 * memory group as Neo4j names it, lower-cased with underscores ({@code bolt} and {@code transaction} in the manual's
 * default filter). The server runs with its Bolt and HTTP connectors enabled so that the connector pools exist.
 */
class MemoryPoolMetricsIT {

    private static final List<String> FIELDS = List.of("used_heap", "used_native", "total_used", "total_size", "free");

    /** Neo4j's memory groups a single-instance server with both connectors creates pools for, in metric spelling. */
    private static final List<String> GLOBAL_POOLS =
            List.of("transaction", "other", "page_cache", "recent_query_buffer", "bolt", "http", "http_transaction");

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithConnectorsAndAnOpenFilter() {
        neo4j = MetricsServers.startWithWebServer(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(BoltConnector.enabled, true));
        db = neo4j.defaultDatabaseService();
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void everyGlobalPoolHasTheFiveDocumentedGauges() {
        Set<String> names = JmxQueries.names(system, "neo4j.metrics:name=neo4j.dbms.pool.*");

        for (String pool : GLOBAL_POOLS) {
            for (String field : FIELDS) {
                String objectName = "neo4j.metrics:name=neo4j.dbms.pool." + pool + "." + field;
                assertThat(names).contains(objectName);
                assertThat(JmxQueries.value(system, objectName)).as(objectName).isInstanceOf(Long.class);
            }
        }
        assertThat(names)
                .allMatch(name -> name.matches("neo4j\\.metrics:name=neo4j\\.dbms\\.pool\\.[a-z0-9_]+\\.[a-z_]+"));
    }

    @Test
    void everyDatabaseHasItsTransactionAndOtherPoolsUnderTheDocumentedDoubleDatabaseTemplate() {
        for (String database : List.of("neo4j", "system")) {
            Set<String> names = JmxQueries.names(system, "neo4j.metrics:name=neo4j.database." + database + ".pool.*");
            for (String pool : List.of("transaction", "other")) {
                for (String field : FIELDS) {
                    String objectName = "neo4j.metrics:name=neo4j.database." + database + ".pool." + pool + "."
                            + database + "." + field;
                    assertThat(names).contains(objectName);
                    assertThat(JmxQueries.value(system, objectName))
                            .as(objectName)
                            .isInstanceOf(Long.class);
                }
            }
        }
    }

    @Test
    void aTransactionThatReservesMemoryShowsInTheDatabasesAndTheGlobalTransactionPool() {
        String databasePool = "neo4j.metrics:name=neo4j.database.neo4j.pool.transaction.neo4j.";
        String globalPool = "neo4j.metrics:name=neo4j.dbms.pool.transaction.";

        try (Transaction tx = db.beginTx()) {
            tx.execute("UNWIND range(1, 20000) AS i CREATE (:Reserved {i: i, text: 'memory for the pool'})")
                    .close();

            long databaseUsedHeap = value(databasePool + "used_heap");
            long databaseTotalUsed = value(databasePool + "total_used");
            long globalUsedHeap = value(globalPool + "used_heap");
            long globalTotalUsed = value(globalPool + "total_used");

            assertThat(databaseUsedHeap)
                    .as("the open transaction's heap is reserved from the database pool")
                    .isPositive();
            assertThat(databaseTotalUsed).isGreaterThanOrEqualTo(databaseUsedHeap);
            assertThat(globalUsedHeap)
                    .as("the global pool aggregates its database pools")
                    .isGreaterThanOrEqualTo(databaseUsedHeap);
            assertThat(globalTotalUsed).isGreaterThanOrEqualTo(globalUsedHeap);
            assertThat(value(globalPool + "total_size")).isPositive();
            assertThat(value(globalPool + "free")).isGreaterThanOrEqualTo(0L);
            tx.rollback();
        }
    }

    private static long value(String objectName) {
        Object value = JmxQueries.value(system, objectName);
        assertThat(value).as("Value of " + objectName).isInstanceOf(Long.class);
        return (Long) value;
    }
}
