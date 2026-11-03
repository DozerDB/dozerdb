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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.neo4j.harness.Neo4j;

/**
 * The JVM metrics beyond the heap (Operations Manual 5, "GC metrics", "JVM memory buffers metrics", "JVM
 * memory pools metrics", "JVM threads metrics", "JVM file descriptor metrics", "JVM pause time metrics") as an
 * administrator observes them through {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.vm.…")}.
 * Each test provokes the JVM event (a garbage collection, an allocation, threads, open files) and reads the metric
 * back. Instance segments ({@code <gc>}, {@code <pool>}, {@code <bufferpool>}) are the MXBean names lower-cased with
 * underscores, as the manual's own examples ({@code g1_eden_space}, {@code direct}) and Aura's
 * ({@code g1_young_generation}) spell them.
 */
class JvmMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.dbms.vm.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithAnOpenFilterAndAFinePauseMonitor() {
        // Neo4j's pause monitor reports a pause once a measurement tick overshoots its interval by the alert
        // threshold (200 ms by default); at one millisecond each, a forced full collection is a detectable pause.
        neo4j = MetricsServers.start(builder -> builder.withConfig(
                        MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                .withConfig(GraphDatabaseInternalSettings.vm_pause_monitor_measurement_duration, Duration.ofMillis(1))
                .withConfig(
                        GraphDatabaseInternalSettings.vm_pause_monitor_stall_alert_threshold, Duration.ofMillis(1)));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void gcCountAndTimeAreCountersPerCollectorNamedLikeTheJvmInLowerCaseThatNeverDecrease() {
        Map<String, Long> countsBefore = counts("gc.count.");
        Map<String, Long> timesBefore = counts("gc.time.");
        assertThat(countsBefore.keySet())
                .as("one count per garbage collector, lower-cased with underscores")
                .hasSize(ManagementFactory.getGarbageCollectorMXBeans().size())
                .allMatch(gc -> gc.matches("[a-z0-9_]+"));
        assertThat(timesBefore.keySet()).isEqualTo(countsBefore.keySet());
        if (ManagementFactory.getGarbageCollectorMXBeans().stream()
                .anyMatch(gc -> gc.getName().startsWith("G1"))) {
            assertThat(countsBefore).containsKeys("g1_young_generation", "g1_old_generation");
        }

        System.gc();

        Map<String, Long> countsAfter = counts("gc.count.");
        Map<String, Long> timesAfter = counts("gc.time.");
        countsAfter.forEach(
                (gc, count) -> assertThat(count).as("count of " + gc).isGreaterThanOrEqualTo(countsBefore.get(gc)));
        timesAfter.forEach(
                (gc, time) -> assertThat(time).as("time of " + gc).isGreaterThanOrEqualTo(timesBefore.get(gc)));
        assertThat(countsAfter.values().stream().mapToLong(Long::longValue).sum())
                .as("a forced collection is counted by some collector")
                .isGreaterThan(countsBefore.values().stream()
                        .mapToLong(Long::longValue)
                        .sum());
    }

    @Test
    void memoryPoolGaugesAreOnePerJvmMemoryPoolAndMoveWhenTheHeapIsAllocated() {
        Map<String, Long> before = values("memory.pool.");
        assertThat(before.keySet())
                .as("one gauge per memory pool, lower-cased with underscores")
                .hasSize(ManagementFactory.getMemoryPoolMXBeans().size())
                .allMatch(pool -> pool.matches("[a-z0-9_]+"));
        assertThat(before).containsKey("metaspace");
        if (ManagementFactory.getMemoryPoolMXBeans().stream()
                .anyMatch(pool -> pool.getName().startsWith("G1"))) {
            assertThat(before).containsKeys("g1_eden_space", "g1_old_gen", "g1_survivor_space");
        }
        before.values().forEach(used -> assertThat(used).isGreaterThanOrEqualTo(0L));

        List<byte[]> held = new ArrayList<>();
        Await.until("a heap memory pool reports a different value after allocating", () -> {
            held.add(new byte[8 * 1024 * 1024]);
            Map<String, Long> now = values("memory.pool.");
            return !now.equals(before);
        });
        assertThat(held).isNotEmpty();
    }

    @Test
    void memoryBufferGaugesCoverTheDirectAndMappedPoolsAndGrowWithADirectBuffer() {
        Set<String> names = JmxQueries.names(db, OBJECT_NAME_PREFIX + "memory.buffer.*");
        for (String pool : List.of("direct", "mapped")) {
            for (String field : List.of("count", "used", "capacity")) {
                String objectName = OBJECT_NAME_PREFIX + "memory.buffer." + pool + "." + field;
                assertThat(names).contains(objectName);
                assertThat(JmxQueries.value(db, objectName)).as(objectName).isInstanceOf(Long.class);
            }
        }
        long capacityBefore = value("memory.buffer.direct.capacity");
        long countBefore = value("memory.buffer.direct.count");

        ByteBuffer direct = ByteBuffer.allocateDirect(16 * 1024 * 1024);

        Await.until(
                "the direct buffer pool grows by the allocated buffer",
                () -> value("memory.buffer.direct.capacity") >= capacityBefore + direct.capacity()
                        && value("memory.buffer.direct.count") > countBefore);
        assertThat(direct.capacity()).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    void liveThreadsAreCountedAndRiseWhenThreadsAreStarted() throws InterruptedException {
        long before = value("threads");
        assertThat(before).isPositive();
        assertThat(JmxQueries.names(db, OBJECT_NAME_PREFIX + "threads*"))
                .as("the 5 reference's JVM threads table has vm.threads only, so nothing else is registered")
                .containsExactly(OBJECT_NAME_PREFIX + "threads");

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(32);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Thread thread = new Thread(
                    () -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    "jvm-metrics-it-" + i);
            thread.start();
            threads.add(thread);
        }
        try {
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
            Await.until("vm.threads counts the started threads", () -> value("threads") >= before + 16);
        } finally {
            release.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
        }
    }

    @Test
    void openFileDescriptorsAreCountedAgainstTheMaximumAndRiseWhenFilesAreOpened(@TempDir Path directory)
            throws IOException {
        assumeTrue(
                ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean,
                "file descriptor metrics exist on Unix-like platforms only");
        long countBefore = value("file.descriptors.count");
        long maximum = value("file.descriptors.maximum");
        assertThat(countBefore).isPositive();
        assertThat(maximum).isGreaterThanOrEqualTo(countBefore);

        List<InputStream> open = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                Path file = Files.writeString(directory.resolve("file-" + i), "fd");
                open.add(Files.newInputStream(file));
            }
            Await.until(
                    "vm.file.descriptors.count counts the opened files",
                    () -> value("file.descriptors.count") >= countBefore + 16);
        } finally {
            for (InputStream stream : open) {
                stream.close();
            }
        }
    }

    /**
     * The Operations Manual 5 has no "CPU usage metrics" section (a later Neo4j added {@code vm.cpu_load.system} and
     * {@code vm.cpu_load.process}), so this line registers no CPU load gauge, even under an open filter.
     */
    @Test
    void noCpuLoadGaugeIsRegisteredBecauseTheManualDocumentsNone() {
        assertThat(JmxQueries.names(db, OBJECT_NAME_PREFIX + "cpu*")).isEmpty();
    }

    @Test
    void pauseTimeAccumulatesThePausesNeo4jsVmPauseMonitorDetects() {
        Object count = JmxQueries.attribute(db, OBJECT_NAME_PREFIX + "pause_time", "Count");
        assertThat(count).as("Count of vm.pause_time").isInstanceOf(Long.class);
        long before = (Long) count;

        List<byte[]> garbage = new ArrayList<>();
        Await.until("a forced collection is detected as a pause and its milliseconds are accumulated", () -> {
            garbage.add(new byte[16 * 1024 * 1024]);
            if (garbage.size() > 4) {
                garbage.clear();
            }
            System.gc();
            return (Long) JmxQueries.attribute(db, OBJECT_NAME_PREFIX + "pause_time", "Count") > before;
        });
    }

    /** The {@code Value} of every gauge under {@code neo4j.dbms.vm.<group>}, keyed by the instance segment. */
    private static Map<String, Long> values(String group) {
        Map<String, Long> values = new TreeMap<>();
        for (String objectName : JmxQueries.names(db, OBJECT_NAME_PREFIX + group + "*")) {
            String instance = objectName.substring((OBJECT_NAME_PREFIX + group).length());
            Object value = JmxQueries.value(db, objectName);
            assertThat(value).as("Value of " + objectName).isInstanceOf(Long.class);
            values.put(instance, (Long) value);
        }
        return values;
    }

    private static long value(String metric) {
        Object value = JmxQueries.value(db, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of " + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} of every counter under {@code neo4j.dbms.vm.<group>}, keyed by the instance segment. */
    private static Map<String, Long> counts(String group) {
        Map<String, Long> counts = new TreeMap<>();
        Set<String> names = JmxQueries.names(db, OBJECT_NAME_PREFIX + group + "*");
        for (String objectName : names) {
            String instance = objectName.substring((OBJECT_NAME_PREFIX + group).length());
            Object count = JmxQueries.attribute(db, objectName, "Count");
            assertThat(count).as("Count of " + objectName).isInstanceOf(Long.class);
            counts.put(instance, (Long) count);
        }
        return counts;
    }
}
