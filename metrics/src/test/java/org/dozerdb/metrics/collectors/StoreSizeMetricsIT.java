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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The database store size metrics (Operations Manual 5, "Database store size metrics") as an administrator
 * observes them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.store.size.<metric>")}. The
 * server runs with the default {@code server.metrics.filter}, whose {@code *store.size*} entry admits the three
 * rows, and checkpoints on its scheduler after every transaction ({@code db.checkpoint.interval.tx=1}, checked
 * every second), because store files grow on disk when the page cache flushes them and Community has no checkpoint
 * procedure. The gauges are read through the system database.
 */
class StoreSizeMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.store.size.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerCheckpointingAfterEveryTransaction() {
        neo4j = MetricsServers.start(builder -> builder.withConfig(GraphDatabaseSettings.check_point_interval_tx, 1)
                .withConfig(GraphDatabaseSettings.check_point_interval_time, Duration.ofSeconds(1)));
        db = neo4j.defaultDatabaseService();
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    /**
     * A started database has store files and a transaction log, so both sizes are positive, and the total size (store
     * files and transaction logs; {@code store.size.total} in the Operations Manual 5, which a later manual renames
     * {@code store.size.full}) is at least the database size (store files alone). The reserved-but-available
     * estimate is a byte count, never negative.
     */
    @Test
    void theTotalSizeCoversTheDatabaseSizeAndTheReservedSpaceEstimateIsNonNegative() {
        long database = gauge("database");
        long total = gauge("total");

        assertThat(database).as("store.size.database").isPositive();
        assertThat(total).as("store.size.total").isGreaterThanOrEqualTo(database);
        assertThat(gauge("available_reserved"))
                .as("store.size.available_reserved")
                .isNotNegative();
    }

    /**
     * Two megabytes of string properties: the commit appends them to the transaction log, so the total size grows at
     * once, and the checkpoint that follows flushes the string store's new pages to disk, so the database size grows
     * too.
     */
    @Test
    void writingDataGrowsTheTotalSizeAndTheDatabaseSizeOnceTheCheckpointFlushesIt() {
        long database = gauge("database");
        long total = gauge("total");

        String filler = "x".repeat(16 * 1024);
        try (Transaction tx = db.beginTx()) {
            for (int node = 0; node < 128; node++) {
                tx.createNode().setProperty("filler", filler);
            }
            tx.commit();
        }

        Await.until(
                "store.size.database grows once the checkpoint flushes the written pages",
                () -> gauge("database") > database);
        assertThat(gauge("total"))
                .as("store.size.total grows with the logs and the store")
                .isGreaterThan(total);
        assertThat(gauge("total")).as("total still covers database").isGreaterThanOrEqualTo(gauge("database"));
    }

    /** The later manual's name for the total size, {@code store.size.full}, is not an Operations Manual 5 row. */
    @Test
    void theLaterManualsNameForTheTotalSizeIsNotRegistered() {
        assertThat(JmxQueries.names(system, OBJECT_NAME_PREFIX + "*"))
                .containsExactlyInAnyOrder(
                        OBJECT_NAME_PREFIX + "total",
                        OBJECT_NAME_PREFIX + "database",
                        OBJECT_NAME_PREFIX + "available_reserved");
    }

    /**
     * Deleted records are reserved space that new data can reuse: the ids of deleted nodes are freed by the id
     * maintenance a checkpoint runs, and the estimate rises by their record sizes.
     */
    @Test
    void deletingNodesRaisesTheReservedButAvailableSpaceOnceTheCheckpointFreesTheirIds() {
        List<String> nodes = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            for (int node = 0; node < 2000; node++) {
                Node created = tx.createNode();
                created.setProperty("reserved", node);
                nodes.add(created.getElementId());
            }
            tx.commit();
        }
        long reserved = gauge("available_reserved");

        try (Transaction tx = db.beginTx()) {
            for (String node : nodes) {
                tx.getNodeByElementId(node).delete();
            }
            tx.commit();
        }

        Await.until(
                "store.size.available_reserved rises once the deleted ids are freed",
                () -> gauge("available_reserved") > reserved);
    }

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of store.size." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }
}
