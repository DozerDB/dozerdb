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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.io.ByteUnit;

/**
 * The page cache metrics (Operations Manual 2026.10, "Database page cache metrics", global despite the heading)
 * as an administrator observes them: {@code CALL dbms.queryJmx("pagecache.metrics:name=pagecache.dbms.page_cache.<metric>")}.
 * The server's page cache is a few megabytes, so writing more data than it holds evicts and flushes pages and
 * reading the data back faults them in again; a checkpoint is scheduled after every transaction because Community
 * has no checkpoint procedure. The server runs beside other tests' servers in the same JVM and therefore has its
 * own metric prefix.
 */
class PageCacheMetricsIT {

    private static final String PREFIX = "pagecache";
    private static final String OBJECT_NAME_PREFIX = PREFIX + ".metrics:name=" + PREFIX + ".dbms.page_cache.";
    private static final Label PAYLOAD = Label.label("Payload");
    private static final String FILLER = "x".repeat(16 * 1024);

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithASmallPageCache() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, PREFIX)
                        .withConfig(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(8))
                        .withConfig(GraphDatabaseSettings.check_point_interval_tx, 1)
                        .withConfig(GraphDatabaseSettings.check_point_interval_time, Duration.ofSeconds(1)));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    /**
     * Reading back more data than the page cache holds hits the pages still cached and faults the evicted ones in
     * from disk again, which reads bytes; the hit ratio is hits over lookups, a ratio.
     */
    @Test
    void readingDataBackHitsAndFaultsAndTheHitRatioIsARatio() {
        writeMoreThanThePageCacheHolds();
        long hits = count("hits");
        long faults = count("page_faults");
        long bytesRead = count("bytes_read");

        readEverythingBack();

        assertThat(count("hits")).as("hits").isGreaterThan(hits);
        assertThat(count("page_faults")).as("page_faults").isGreaterThan(faults);
        assertThat(count("bytes_read")).as("bytes_read").isGreaterThan(bytesRead);
        assertThat(ratio("hit_ratio")).as("hit_ratio").isBetween(0.0, 1.0).isGreaterThan(0.0);
    }

    /**
     * Writing more than the page cache holds pins and unpins pages, faults them in, and evicts others to make room;
     * the evicted pages are dirty, so eviction flushes them, and that writes bytes. Cooperative evictions are those a
     * pinning thread performs itself when the free list is empty, which a burst into a small cache causes.
     */
    @Test
    void writingMoreThanThePageCacheHoldsFaultsEvictsAndFlushes() {
        Map<String, Long> before = counts();

        writeMoreThanThePageCacheHolds();

        Map<String, Long> after = counts();
        for (String metric : List.of(
                "pins",
                "unpins",
                "page_faults",
                "evictions",
                "evictions.cooperative",
                "eviction.flushes",
                "eviction.cooperative.flushes",
                "flushes",
                "bytes_written")) {
            assertThat(after.get(metric)).as(metric).isGreaterThan(before.get(metric));
        }
    }

    /**
     * A checkpoint flushes the dirty pages of every store file, which is where the page cache counts its IO
     * operations, and writes adjacent dirty pages as one operation, which it counts as merges. Merges therefore
     * need the transaction's pages to be still dirty and adjacent in the cache when the checkpoint comes; in the
     * shared eight megabyte server the eviction thread may have written them out before then, so this test runs
     * its own server whose page cache holds many times the data written. Muninn's eviction thread parks until
     * fewer than its reserve of pages are free, so nothing is evicted and the dirty pages wait for the checkpoint,
     * which is bound to merge them. Community has no checkpoint procedure, so the server checkpoints after every
     * transaction on its scheduler, which checks every ten seconds. The server needs its own prefix beside the
     * shared one in this JVM.
     */
    @Test
    void aCheckpointFlushesWithIoOperationsAndMergesPages() {
        String prefix = "checkpointed";
        String objectNamePrefix = prefix + ".metrics:name=" + prefix + ".dbms.page_cache.";
        try (Neo4j roomy = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, prefix)
                        .withConfig(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(64))
                        .withConfig(GraphDatabaseSettings.check_point_interval_tx, 1)
                        .withConfig(GraphDatabaseSettings.check_point_interval_time, Duration.ofSeconds(1)))) {
            GraphDatabaseService roomyDb = roomy.defaultDatabaseService();
            long ioOperations = count(roomyDb, objectNamePrefix, "iops");
            long merges = count(roomyDb, objectNamePrefix, "merges");
            long flushes = count(roomyDb, objectNamePrefix, "flushes");
            long bytesWritten = count(roomyDb, objectNamePrefix, "bytes_written");

            try (Transaction tx = roomyDb.beginTx()) {
                for (int node = 0; node < 64; node++) {
                    tx.createNode(PAYLOAD).setProperty("filler", FILLER);
                }
                tx.commit();
            }

            // An index checkpoint (the label token index populating for the new label) counts IO operations too
            // but flushes no adjacent dirty pages; the merges identify the checkpoint that flushed the transaction.
            Await.until(
                    "the scheduled checkpoint performs IO operations and merges the transaction's adjacent dirty pages",
                    () -> count(roomyDb, objectNamePrefix, "iops") > ioOperations
                            && count(roomyDb, objectNamePrefix, "merges") > merges);
            assertThat(count(roomyDb, objectNamePrefix, "flushes"))
                    .as("flushes")
                    .isGreaterThan(flushes);
            assertThat(count(roomyDb, objectNamePrefix, "bytes_written"))
                    .as("bytes_written")
                    .isGreaterThan(bytesWritten);
        }
    }

    @Test
    void theUsageRatioIsTheUsedShareOfTheCachedPages() {
        writeMoreThanThePageCacheHolds();

        assertThat(ratio("usage_ratio")).as("usage_ratio").isBetween(0.0, 1.0).isGreaterThan(0.5);
    }

    /**
     * Eleven counters count events only an Enterprise mechanism produces: no-fault cursors, the page prefetcher's
     * vectored faults, the checkpoint IO limiter, multi-version page copies and snapshots, and native asynchronous
     * IO. Community never drives them; they are registered, typed as counters, and stay zero through the workload.
     * Their inventory rows are Partial for that reason; a Neo4j upgrade that starts driving one fails this test.
     */
    @Test
    void countersOfEventsCommunityNeverProducesStayZero() {
        writeMoreThanThePageCacheHolds();
        readEverythingBack();

        for (String metric : List.of(
                "page_cancelled_faults",
                "page_vectored_faults",
                "page_vectored_faults_failures",
                "page_no_pin_page_faults",
                "throttled.times",
                "throttled.millis",
                "pages_copied",
                "pages_snapshot_loaded",
                "async_submit",
                "async_complete",
                "async_failure")) {
            assertThat(count(metric)).as(metric).isZero();
        }
    }

    /** The two failure counters Community does drive count nothing while no page cache IO fails. */
    @Test
    void failureCountersStayZeroWhileNothingFails() {
        writeMoreThanThePageCacheHolds();
        readEverythingBack();

        assertThat(count("eviction_exceptions")).as("eviction_exceptions").isZero();
        assertThat(count("page_fault_failures")).as("page_fault_failures").isZero();
    }

    private static final List<String> COUNTERS = List.of(
            "eviction_exceptions",
            "flushes",
            "merges",
            "unpins",
            "pins",
            "evictions",
            "evictions.cooperative",
            "eviction.flushes",
            "eviction.cooperative.flushes",
            "page_faults",
            "page_fault_failures",
            "page_cancelled_faults",
            "page_vectored_faults",
            "page_vectored_faults_failures",
            "page_no_pin_page_faults",
            "hits",
            "bytes_read",
            "bytes_written",
            "iops",
            "throttled.times",
            "throttled.millis",
            "pages_copied",
            "pages_snapshot_loaded",
            "async_submit",
            "async_complete",
            "async_failure");

    /** Every page cache counter's count, keyed by metric. */
    private static Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String metric : COUNTERS) {
            counts.put(metric, count(metric));
        }
        return counts;
    }

    /** Twelve megabytes of string properties, in transactions of four megabytes, into an eight megabyte page cache. */
    private static void writeMoreThanThePageCacheHolds() {
        for (int transaction = 0; transaction < 3; transaction++) {
            try (Transaction tx = db.beginTx()) {
                for (int node = 0; node < 256; node++) {
                    tx.createNode(PAYLOAD).setProperty("filler", FILLER);
                }
                tx.commit();
            }
        }
    }

    private static long readEverythingBack() {
        try (Transaction tx = db.beginTx()) {
            return (Long) tx.execute("MATCH (n:Payload) RETURN sum(size(n.filler)) AS bytes")
                    .next()
                    .get("bytes");
        }
    }

    /** The {@code Count} attribute of a page cache counter, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        return count(db, OBJECT_NAME_PREFIX, metric);
    }

    private static long count(GraphDatabaseService database, String objectNamePrefix, String metric) {
        Object count = JmxQueries.attribute(database, objectNamePrefix + metric, "Count");
        assertThat(count).as("Count of page_cache." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }

    /** The {@code Value} attribute of a ratio gauge. */
    private static double ratio(String metric) {
        Object value = JmxQueries.value(db, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of page_cache." + metric).isInstanceOf(Double.class);
        return (Double) value;
    }
}
