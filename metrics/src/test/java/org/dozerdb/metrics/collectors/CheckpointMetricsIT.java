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
import java.util.Map;
import java.util.Optional;
import org.dozerdb.metrics.Await;
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
import org.neo4j.io.ByteUnit;

/**
 * The database checkpointing metrics (Operations Manual 5, "Database checkpointing metrics") as an
 * administrator observes them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.check_point.<metric>")}.
 * Community has no checkpoint procedure ({@code db.checkpoint()} is Enterprise), so the server checkpoints on its
 * scheduler after every transaction ({@code db.checkpoint.interval.tx=1}) and the scheduler checks every second
 * ({@code db.checkpoint.interval.time=1s}, which is also the check frequency); each test commits a write and waits
 * for the checkpoint that follows it.
 *
 * <p>The server's page cache is 64 MiB, not the harness's 8 MiB, so that the pages a test dirties are still in the
 * cache when that checkpoint comes: Muninn's eviction thread parks until fewer than its reserve of pages are free
 * (5 % of the cache, 51 pages of 8 MiB, 409 of 64 MiB; {@code MuninnPageCache.parkUntilEvictionRequired}), and this
 * class writes well under a megabyte. In the 8 MiB cache the database the lifecycle test creates could fill the
 * cache and wake the evictor, which writes dirty pages out itself; a checkpoint that follows found nothing to flush
 * and reported {@code pages_flushed} 0 for good (no further transaction, no further checkpoint), so a test waiting
 * for a checkpoint with flushed pages timed out once in a loaded fork. The checkpoint itself is never slow to come:
 * the count threshold fires on the first append after a checkpoint and the scheduler checks every second.
 */
class CheckpointMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.check_point.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerCheckpointingAfterEveryTransaction() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(64))
                        .withConfig(GraphDatabaseSettings.check_point_interval_tx, 1)
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

    @Test
    void aCheckpointAfterACommittedWriteIsCountedAsAnEventAndAddsToTheTotalTime() {
        long events = count("events");
        long totalTime = count("total_time");

        commitAWrite(1);

        Await.until("check_point.events rises after the committed write", () -> count("events") > events);
        assertThat(count("total_time")).as("total_time never decreases").isGreaterThanOrEqualTo(totalTime);
        assertThat(gauge("duration"))
                .as("duration of the last checkpoint in ms")
                .isGreaterThanOrEqualTo(0L);
    }

    /**
     * The pages a checkpoint flushes are the pages the transactions since the previous checkpoint dirtied, and the
     * IOs are those the page cache issued to write them; both describe the last checkpoint. The flushed bytes
     * accumulate over every checkpoint of the database. The test waits for a checkpoint that both follows the write
     * and reports flushed pages, because a checkpoint already running when the write committed completes first and
     * may report only what was dirty before it; with the 64 MiB page cache (class Javadoc) the write's pages cannot
     * have been evicted meanwhile, so the checkpoint that follows the write is bound to flush them.
     */
    @Test
    void aCheckpointAfterWritingDataFlushesPagesPerformsIoAndAddsToTheFlushedBytes() {
        long events = count("events");
        long flushedBytes = count("flushed_bytes");

        commitAWrite(64);

        Await.until(
                "a checkpoint after the write reports its flushed pages",
                () -> count("events") > events && gauge("pages_flushed") > 0);
        assertThat(gauge("io_performed")).as("IOs of the last checkpoint").isGreaterThan(0L);
        assertThat(count("flushed_bytes")).as("flushed_bytes accumulates").isGreaterThan(flushedBytes);
    }

    /**
     * Neo4j records the IO limiter's configured limit and how often and how long it paused the last checkpoint.
     * Community's edition wires the disabled IO controller ({@code CommunityIOControllerService}), which reports no
     * limit and never pauses, so these three gauges read 0 here however {@code db.checkpoint.iops.limit} is set;
     * the inventory tiers them Partial for that reason.
     */
    @Test
    void theIoLimiterRowsReadZeroBecauseCommunityHasNoCheckpointIoLimiter() {
        long events = count("events");
        commitAWrite(1);
        Await.until("a checkpoint completes after the write", () -> count("events") > events);

        assertThat(gauge("io_limit")).as("io_limit").isZero();
        assertThat(gauge("limit_times")).as("limit_times").isZero();
        assertThat(gauge("limit_millis")).as("limit_millis").isZero();
    }

    /**
     * Checkpoint metrics follow the database (Operations Manual 5: "Database metrics are only available during
     * the lifetime of the database"): a created database brings its own {@code check_point.events}, a dropped one
     * takes it away. DozerDB does not accept the {@code WAIT} clause, so the test polls {@code SHOW DATABASES}.
     */
    @Test
    void aCreatedDatabaseHasItsOwnCheckpointMetricsUntilItIsDropped() {
        String database = "checkpointed";
        String events = "neo4j.metrics:name=neo4j.database." + database + ".check_point.events";
        execute("CREATE DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        assertThat(JmxQueries.attribute(system, events, "Count"))
                .as("check_point.events of the created database")
                .isInstanceOf(Long.class);

        execute("DROP DATABASE " + database);
        awaitStatus(database, Optional.empty());
        assertThat(JmxQueries.names(system, "neo4j.metrics:name=neo4j.database." + database + ".*"))
                .as("metrics of the dropped database")
                .isEmpty();
    }

    private static void execute(String cypher) {
        try (Transaction tx = system.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    private static void awaitStatus(String database, Optional<String> status) {
        Await.until(database + " has status " + status.orElse("<absent>"), () -> {
            try (Transaction tx = system.beginTx()) {
                return tx
                        .execute(
                                "SHOW DATABASES YIELD name, currentStatus WHERE name = $name RETURN currentStatus",
                                Map.of("name", database))
                        .stream()
                        .map(row -> (String) row.get("currentStatus"))
                        .findFirst()
                        .equals(status);
            }
        });
    }

    private static void commitAWrite(int nodes) {
        try (Transaction tx = db.beginTx()) {
            for (int node = 0; node < nodes; node++) {
                tx.createNode().setProperty("filler", "x".repeat(1024));
            }
            tx.commit();
        }
    }

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of check_point." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} attribute of a counter of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(system, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of check_point." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }
}
