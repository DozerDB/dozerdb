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

import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The database transaction log metrics (Operations Manual 2026.10, "Database transaction log metrics") as an
 * administrator observes them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.log.<metric>")}.
 * The server rotates its transaction log at the smallest allowed size (128 KiB), so that a few transactions carrying
 * 16 KiB strings rotate it; each test provokes the event on the default database and reads the metrics through the
 * system database.
 */
class TransactionLogMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.log.";
    private static final int ROTATION_SIZE = 128 * 1024;

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerRotatingSmallLogs() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(GraphDatabaseSettings.logical_log_rotation_threshold, (long) ROTATION_SIZE));
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
     * Neo4j rotates the transaction log when an append finds the file beyond the rotation threshold, on the committing
     * thread, so the rotations are counted once the commits have returned.
     */
    @Test
    void appendingMoreThanTheRotationThresholdRotatesTheLogAndIsCountedAndTimed() {
        long rotations = count("rotation_events");
        long rotationTotalTime = count("rotation_total_time");

        commitBytes(3 * ROTATION_SIZE);

        assertThat(count("rotation_events")).as("rotation_events").isGreaterThan(rotations);
        assertThat(count("rotation_total_time"))
                .as("rotation_total_time never decreases")
                .isGreaterThanOrEqualTo(rotationTotalTime);
        assertThat(gauge("rotation_duration"))
                .as("duration of the last rotation in ms")
                .isGreaterThanOrEqualTo(0L);
    }

    /**
     * Every committed write appends its commands to the transaction log and forces the file, so the appended bytes
     * grow by at least the data written, the flushes rise, and the last append batch is the one transaction just
     * committed.
     */
    @Test
    void aCommittedWriteAppendsItsBytesFlushesTheLogAndIsTheLastAppendBatch() {
        long appendedBytes = count("appended_bytes");
        long flushes = count("flushes");
        int written = 4 * 16 * 1024;

        commitBytes(written);

        assertThat(count("appended_bytes"))
                .as("appended_bytes grows by at least the written data")
                .isGreaterThanOrEqualTo(appendedBytes + written);
        assertThat(count("flushes")).as("flushes").isGreaterThan(flushes);
        assertThat(gauge("append_batch_size"))
                .as("transactions in the last append batch")
                .isGreaterThanOrEqualTo(1L);
    }

    /** Commits transactions of eight 16 KiB strings each until at least {@code bytes} of property data are written. */
    private static void commitBytes(int bytes) {
        String filler = "x".repeat(16 * 1024);
        int written = 0;
        while (written < bytes) {
            try (Transaction tx = db.beginTx()) {
                for (int node = 0; node < 8; node++) {
                    tx.createNode().setProperty("filler", filler);
                    written += filler.length();
                }
                tx.commit();
            }
        }
    }

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of log." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} attribute of a counter of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(system, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of log." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }
}
