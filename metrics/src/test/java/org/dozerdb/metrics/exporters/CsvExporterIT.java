/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.adapter.MetricsInternalSettings;
import org.dozerdb.metrics.extension.FailingAtReadCollector;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * The CSV exporter as an administrator sees it: files in {@code server.directories.metrics}. With default
 * settings (CSV enabled, the default filter) and the minimum interval, the directory gains one file per
 * registered metric, named by the full metric name, in Dropwizard's CSV layout: a header once per file
 * ({@code t,value} for a gauge, {@code t,count} for a counter, the percentile columns for a histogram) and one row
 * per interval whose {@code t} is epoch seconds, numbers formatted as Dropwizard's {@code CsvReporter} formats them
 * whatever the default locale. The {@code csv.enabled} switch, the relative default directory, the first report one
 * interval after start, the isolation of a directory that is unusable at start or becomes unusable later, and the
 * stop are observed the same way. Nothing here reads the registry; rotation is {@code CsvRotationIT}.
 */
class CsvExporterIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=";
    private static final String HEAP_USED = "neo4j.dbms.vm.heap.used";
    private static final String HEAP_USED_FILE = HEAP_USED + ".csv";
    private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(1);

    @Test
    void everyRegisteredMetricGetsAFileNamedByItsFullNameWithTheGaugeHeaderAndEpochSecondRows(@TempDir Path dir) {
        long startedAt = Instant.now().getEpochSecond();
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Set<String> registered = JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:*").stream()
                    .map(name -> name.substring(OBJECT_NAME_PREFIX.length()) + ".csv")
                    .collect(Collectors.toCollection(TreeSet::new));

            Await.until("a CSV file exists per registered metric", () -> csvFiles(dir)
                    .equals(registered));
            assertThat(csvFiles(dir)).contains(HEAP_USED_FILE);

            Path heapUsed = dir.resolve(HEAP_USED_FILE);
            Await.until("three lines are written", () -> lines(heapUsed).size() >= 3);
            List<String> lines = lines(heapUsed);
            assertThat(lines.get(0)).isEqualTo("t,value");
            String[] row = lines.get(1).split(",");
            assertThat(row).hasSize(2);
            assertThat(Long.parseLong(row[0]))
                    .as("t is epoch seconds")
                    .isBetween(startedAt, Instant.now().getEpochSecond() + 1);
            assertThat(Long.parseLong(row[1])).as("heap used in bytes").isPositive();
        }
    }

    /**
     * The expected rows follow the format strings of Dropwizard's {@code CsvReporter} (metrics-core 4.2.40), not the
     * writer: a gauge's value as is, a counter's count with {@code %d}, a histogram's count, max and min with
     * {@code %d} and its mean, standard deviation and six percentiles with {@code %f}, always in the US locale. The
     * default locale is German while the server runs, so a locale leak would write {@code 2,000000} and break the
     * column count. Every file has its header once, on the first line.
     */
    @Test
    void eachMetricTypeWritesDropwizardsColumnsInTheUsLocaleWhateverTheDefaultLocale(@TempDir Path dir) {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                .withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            for (int i = 0; i < 3; i++) {
                try (var tx = neo4j.defaultDatabaseService().beginTx()) {
                    tx.createNode().setProperty("size", "x".repeat(100 * (i + 1)));
                    tx.commit();
                }
            }
            Path heapUsed = dir.resolve(HEAP_USED_FILE);
            Path hitRatio = dir.resolve("neo4j.dbms.page_cache.hit_ratio.csv");
            Path committed = dir.resolve("neo4j.database.neo4j.transaction.committed.csv");
            Path heapSizes = dir.resolve("neo4j.database.neo4j.transaction.tx_size_heap.csv");
            Await.until(
                    "every file has a header and three rows", () -> Stream.of(heapUsed, hitRatio, committed, heapSizes)
                            .allMatch(file -> lines(file).size() >= 4));

            assertThat(lines(heapUsed)).first().isEqualTo("t,value");
            assertThat(completeRows(heapUsed)).allMatch(row -> row.matches("\\d+,\\d+"), "t and a long");
            assertThat(lines(hitRatio)).first().isEqualTo("t,value");
            assertThat(completeRows(hitRatio)).allSatisfy(row -> assertThat(Double.parseDouble(row.split(",")[1]))
                    .isBetween(0.0, 1.0));
            assertThat(lines(committed)).first().isEqualTo("t,count");
            assertThat(completeRows(committed)).allMatch(row -> row.matches("\\d+,\\d+"), "t and a count");
            assertThat(lines(heapSizes)).first().isEqualTo("t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999");
            String decimal = "-?\\d+\\.\\d{6}";
            assertThat(completeRows(heapSizes))
                    .allMatch(
                            row -> row.matches(
                                    "\\d+,\\d+,\\d+," + decimal + ",\\d+," + decimal + "(," + decimal + "){6}"),
                            "t, count, max, mean, min, stddev and six percentiles");
            for (Path file : List.of(heapUsed, hitRatio, committed, heapSizes)) {
                assertThat(completeRows(file))
                        .as("the header is written once, at the top of " + file.getFileName())
                        .noneMatch(line -> line.startsWith("t,"));
            }
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    /** A gauge whose read fails reports no value, so it writes no row and its file is never created. */
    @Test
    void aGaugeWithoutAValueWritesNoRow(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                .withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                .withConfig(
                        MetricsInternalSettings.additional_collectors,
                        List.of(FailingAtReadCollector.class.getName())))) {
            Await.until(
                    "the heap gauge has three rows",
                    () -> lines(dir.resolve(HEAP_USED_FILE)).size() >= 4);

            assertThat(csvFiles(dir))
                    .as("the failing gauge has no file")
                    .doesNotContain("neo4j.dbms." + FailingAtReadCollector.NAME + ".value.csv");
        }
    }

    /** The first row lands one interval after start: with an interval of an hour, the directory exists and is empty. */
    @Test
    void startCreatesTheDirectoryButTheFirstRowWaitsOneInterval(@TempDir Path dir) {
        Path metrics = dir.resolve("metrics");
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, metrics)
                .withConfig(MetricsSettings.csv_interval, Duration.ofHours(1)))) {
            assertThat(serves(neo4j.defaultDatabaseService())).isTrue();

            assertThat(metrics).isDirectory().isEmptyDirectory();
        }
    }

    /**
     * A directory that becomes unusable while the server runs (moved away and replaced by a file) fails the next
     * report: one error line, the exporter disabled, the server serving. A directory put back at the path stays empty,
     * because the disabled exporter never reports again.
     */
    @Test
    void aWriteFailureAtRuntimeDisablesTheExporterWithOneErrorLineAndNoLaterReport(@TempDir Path dir)
            throws IOException {
        Path metrics = dir.resolve("metrics");
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, metrics)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Await.until("the heap gauge file exists", () -> csvFiles(metrics).contains(HEAP_USED_FILE));

            Files.move(metrics, dir.resolve("moved"));
            Files.writeString(metrics, "no longer a directory");
            Await.until("the failure is logged", () -> errorLines(neo4j, CsvExporter.NAME) == 1);
            Files.delete(metrics);
            Files.createDirectory(metrics);
            pauseForReports();

            assertThat(metrics).as("no report after the failure").isEmptyDirectory();
            assertThat(errorLines(neo4j, CsvExporter.NAME)).isEqualTo(1);
            assertThat(serves(neo4j.defaultDatabaseService())).isTrue();
        }
    }

    /** Stopping the server stops the reports: the live file does not grow afterwards. */
    @Test
    void noRowIsWrittenAfterTheServerStops(@TempDir Path dir) {
        Path heapUsed = dir.resolve(HEAP_USED_FILE);
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Await.until("three rows are written", () -> lines(heapUsed).size() >= 4);
        }
        List<String> atStop = lines(heapUsed);

        pauseForReports();

        assertThat(lines(heapUsed)).isEqualTo(atStop);
    }

    @Test
    void theExporterWritesOnTheSchedulerGroupNeo4jDefinesForCsvMetrics(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Await.until("the heap gauge file exists", () -> csvFiles(dir).contains(HEAP_USED_FILE));

            assertThat(Thread.getAllStackTraces().keySet())
                    .extracting(Thread::getName)
                    .as("a thread of Neo4j's MetricsCsvWrite scheduler group")
                    .anyMatch(name -> name.startsWith("neo4j.MetricsCsvWrite-"));
        }
    }

    /**
     * Database metrics reach the files with their type's header: a counter reading Neo4j's own count writes
     * {@code t,count}, a histogram the percentile columns. The server runs alone, so the default prefix is free.
     */
    @Test
    void databaseCountersAndHistogramsGetTheirTypeHeaders(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                .withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            try (var tx = neo4j.defaultDatabaseService().beginTx()) {
                tx.createNode();
                tx.commit();
            }
            Path committed = dir.resolve("neo4j.database.neo4j.transaction.committed.csv");
            Path heapSizes = dir.resolve("neo4j.database.neo4j.transaction.tx_size_heap.csv");

            Await.until(
                    "the committed counter and the heap size histogram have a header and a row",
                    () -> lines(committed).size() >= 2 && lines(heapSizes).size() >= 2);

            assertThat(lines(committed).get(0)).isEqualTo("t,count");
            assertThat(Long.parseLong(lines(committed).get(1).split(",")[1]))
                    .as("committed transactions")
                    .isGreaterThanOrEqualTo(1);
            assertThat(lines(heapSizes).get(0)).isEqualTo("t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999");
            assertThat(lines(heapSizes).get(1).split(",")).hasSize(12);
        }
    }

    /**
     * A global counter reaches the files under the default filter, which admits {@code *page_cache.hit*}: the page
     * cache hits file carries the counter header, and the server's own start has already hit pages.
     */
    @Test
    void aGlobalCounterGetsTheCounterHeaderUnderTheDefaultFilter(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Path hits = dir.resolve("neo4j.dbms.page_cache.hits.csv");

            Await.until(
                    "the page cache hits counter has a header and a row",
                    () -> lines(hits).size() >= 2);

            assertThat(lines(hits).get(0)).isEqualTo("t,count");
            assertThat(Long.parseLong(lines(hits).get(1).split(",")[1]))
                    .as("page cache hits")
                    .isPositive();
        }
    }

    @Test
    void aRelativeMetricsDirectoryResolvesFromTheNeo4jHome() {
        try (Neo4j neo4j =
                MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Path home = neo4j.config().get(GraphDatabaseSettings.neo4j_home);

            Await.until(
                    "the default directory <home>/metrics holds the heap gauge file",
                    () -> csvFiles(home.resolve("metrics")).contains(HEAP_USED_FILE));
        }
    }

    @Test
    void disabledCsvExportWritesNothing(@TempDir Path dir) {
        Path metrics = dir.resolve("metrics");
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, metrics)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                .withConfig(MetricsSettings.csv_enabled, false))) {
            assertThat(serves(neo4j.defaultDatabaseService())).isTrue();
            assertThat(JmxQueries.value(neo4j.defaultDatabaseService(), OBJECT_NAME_PREFIX + HEAP_USED))
                    .as("the JMX exporter is unaffected")
                    .isNotNull();

            assertThat(metrics).as("the metrics directory is never created").doesNotExist();
        }
    }

    @Test
    void anUncreatableMetricsDirectoryDisablesTheExporterWithOneErrorLineWhileTheServerServes(@TempDir Path dir)
            throws IOException {
        Path file = Files.writeString(dir.resolve("a-file"), "not a directory");
        try (Neo4j neo4j =
                MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, file.resolve("metrics"))
                        .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();

            assertThat(serves(db)).isTrue();
            assertThat(JmxQueries.value(db, OBJECT_NAME_PREFIX + HEAP_USED))
                    .as("the JMX exporter is unaffected")
                    .isNotNull();
            assertThat(errorLinesAbout(neo4j, CsvExporter.NAME)).hasSize(1);
            assertThat(csvFiles(dir)).isEmpty();
        }
    }

    private static boolean serves(GraphDatabaseService db) {
        try (var tx = db.beginTx()) {
            return tx.execute("RETURN 1 AS one").next().get("one").equals(1L);
        }
    }

    /**
     * Waits long enough for a few hundred reports at the minimum interval: the one fixed wait of these tests, for an
     * observation that nothing happens, where no later event could be awaited instead.
     */
    static void pauseForReports() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static long errorLines(Neo4j neo4j, String component) {
        try {
            return errorLinesAbout(neo4j, component).size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> errorLinesAbout(Neo4j neo4j, String component) throws IOException {
        Path debugLog = neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        return Files.readAllLines(debugLog).stream()
                .filter(line -> line.contains("ERROR") && line.contains("'" + component + "'"))
                .toList();
    }

    static Set<String> csvFiles(Path dir) {
        return files(dir).stream().filter(name -> name.endsWith(".csv")).collect(Collectors.toCollection(TreeSet::new));
    }

    static Set<String> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rows of a file, without the header and without the last line, which a report may be writing. */
    private static List<String> completeRows(Path file) {
        List<String> lines = lines(file);
        return lines.subList(1, Math.max(1, lines.size() - 1));
    }

    static List<String> lines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
