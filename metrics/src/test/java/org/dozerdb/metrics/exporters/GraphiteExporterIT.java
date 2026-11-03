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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * The Graphite exporter as a Graphite server sees it: plaintext lines {@code <name> <value> <epoch seconds>} on a
 * TCP connection to {@code server.metrics.graphite.server}, one report per {@code server.metrics.graphite.interval}.
 * A gauge is one line named by its full metric name, prefix included exactly once. The exporter is off by default,
 * reports on the scheduler group Neo4j defines for Graphite metrics, sends nothing before the first interval and no
 * final report at stop, logs an unreachable server once per outage while Cypher and JMX keep working, reaches a
 * server that comes up later, and is disabled with one error line by a failure that is not an outage.
 *
 * <p>The interval has no documented minimum; the tests use a tenth of a second, short enough to observe several
 * reports and long enough not to churn a TCP connection per millisecond, since the reporter connects per report.
 * Where a test must know that several reports have passed, the CSV exporter at the same interval is the clock: its
 * rows count the intervals.
 */
class GraphiteExporterIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=";
    private static final String HEAP_USED = "neo4j.dbms.vm.heap.used";
    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final Pattern PLAINTEXT_LINE = Pattern.compile("^(\\S+) (\\S+) (\\d+)$");

    @Test
    void everyRegisteredMetricArrivesAsAPlaintextLineNamedByItsFullMetricNameWithAnEpochSecondTimestamp()
            throws IOException {
        long startedAt = Instant.now().getEpochSecond();
        try (GraphiteListener graphite = GraphiteListener.listen();
                Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                        .withConfig(MetricsSettings.graphite_server, graphite.address())
                        .withConfig(MetricsSettings.graphite_interval, INTERVAL)
                        .withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            Set<String> registered = JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:*").stream()
                    .map(name -> name.substring(OBJECT_NAME_PREFIX.length()))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(registered).contains(HEAP_USED, "neo4j.dbms.vm.heap.committed", "neo4j.dbms.vm.heap.max");

            Await.until("every registered metric has reached Graphite", () -> registered.stream()
                    .allMatch(metric -> covers(graphite.lines(), metric)));

            List<String> lines = graphite.lines();
            assertThat(lines).allSatisfy(line -> assertThat(line).matches(PLAINTEXT_LINE));
            assertThat(lines)
                    .extracting(GraphiteExporterIT::name)
                    .as("the metric prefix appears exactly once")
                    .allMatch(name -> name.startsWith("neo4j.dbms.") || name.startsWith("neo4j.database."))
                    .noneMatch(name -> name.startsWith("neo4j.neo4j."));
            assertThat(lines)
                    .extracting(GraphiteExporterIT::timestamp)
                    .as("timestamps are epoch seconds of the test run")
                    .allMatch(t -> t >= startedAt && t <= Instant.now().getEpochSecond() + 1);

            String heapUsed = lines.stream()
                    .filter(line -> name(line).equals(HEAP_USED))
                    .findFirst()
                    .orElseThrow();
            assertThat(Long.parseLong(value(heapUsed))).as("heap used in bytes").isPositive();
        }
    }

    /** Each report is one connection that carries every registered metric once and is closed at the report's end. */
    @Test
    void eachReportIsOneConnectionCarryingEveryMetricOnce() throws IOException {
        try (GraphiteListener graphite = GraphiteListener.listen();
                Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                        .withConfig(MetricsSettings.graphite_server, graphite.address())
                        .withConfig(MetricsSettings.graphite_interval, INTERVAL))) {
            Await.until("three reports have arrived", () -> graphite.reports().size() >= 3);

            assertThat(graphite.reports()).allSatisfy(report -> {
                assertThat(report)
                        .filteredOn(line -> name(line).equals(HEAP_USED))
                        .hasSize(1);
                assertThat(report.stream()
                                .map(GraphiteExporterIT::name)
                                .distinct()
                                .count())
                        .as("no path twice in one report")
                        .isEqualTo(report.size());
            });
        }
    }

    /**
     * With an interval of an hour nothing connects while the server runs, and stopping the server sends no final
     * report: the listener never sees a connection.
     */
    @Test
    void nothingIsSentBeforeTheFirstIntervalOrAtStop() throws IOException {
        try (GraphiteListener graphite = GraphiteListener.listen()) {
            try (Neo4j neo4j =
                    MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                            .withConfig(MetricsSettings.graphite_server, graphite.address())
                            .withConfig(MetricsSettings.graphite_interval, Duration.ofHours(1)))) {
                assertThat(serves(neo4j.defaultDatabaseService())).isTrue();
            }
            CsvExporterIT.pauseForReports();

            assertThat(graphite.connections()).isZero();
        }
    }

    /**
     * A failure inside a report that is not an outage disables the exporter once: a port beyond 65535 passes settings
     * validation but cannot form a socket address, so the first report throws. One component error line, no further
     * attempt, the server serving.
     */
    @Test
    void aFailureThatIsNotAnOutageDisablesTheExporterWithOneErrorLine(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                .withConfig(MetricsSettings.graphite_server, new SocketAddress("127.0.0.1", 70_000))
                .withConfig(MetricsSettings.graphite_interval, INTERVAL)
                .withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, INTERVAL))) {
            Await.until("the failure is logged", () -> errorLinesAbout(neo4j).size() == 1);
            long rowsAtFailure = csvRows(dir);
            Await.until("five more intervals have passed", () -> csvRows(dir) >= rowsAtFailure + 5);

            assertThat(errorLinesAbout(neo4j))
                    .singleElement()
                    .asString()
                    .contains("Metrics component '" + GraphiteExporter.NAME + "' failed and is disabled");
            assertThat(serves(neo4j.defaultDatabaseService())).isTrue();
        }
    }

    @Test
    void theExporterReportsOnTheSchedulerGroupNeo4jDefinesForGraphiteMetrics() throws IOException {
        try (GraphiteListener graphite = GraphiteListener.listen();
                Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                        .withConfig(MetricsSettings.graphite_server, graphite.address())
                        .withConfig(MetricsSettings.graphite_interval, INTERVAL))) {
            Await.until("the heap gauge reached Graphite", () -> covers(graphite.lines(), HEAP_USED));

            assertThat(Thread.getAllStackTraces().keySet())
                    .extracting(Thread::getName)
                    .as("a thread of Neo4j's MetricsGraphiteWrite scheduler group")
                    .anyMatch(name -> name.startsWith("neo4j.MetricsGraphiteWrite-"));
        }
    }

    @Test
    void graphiteIsOffByDefaultSoNothingConnects(@TempDir Path dir) throws IOException {
        try (GraphiteListener graphite = GraphiteListener.listen();
                Neo4j neo4j = MetricsServers.start(
                        builder -> builder.withConfig(MetricsSettings.graphite_server, graphite.address())
                                .withConfig(MetricsSettings.graphite_interval, INTERVAL)
                                .withConfig(MetricsSettings.csv_path, dir)
                                .withConfig(MetricsSettings.csv_interval, INTERVAL))) {
            Await.until("five intervals have passed", () -> csvRows(dir) >= 5);

            assertThat(neo4j.config().get(MetricsSettings.graphite_enabled)).isFalse();
            assertThat(graphite.connections()).as("no connection was attempted").isZero();
        }
    }

    @Test
    void anUnreachableServerIsLoggedOncePerOutageWhileTheServerServesAndReceivesMetricsOnceItComesUp(@TempDir Path dir)
            throws IOException {
        int port = GraphiteListener.freePort();
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.graphite_enabled, true)
                .withConfig(MetricsSettings.graphite_server, GraphiteListener.loopback(port))
                .withConfig(MetricsSettings.graphite_interval, INTERVAL)
                .withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, INTERVAL))) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();

            Await.until(
                    "the unreachable server is logged",
                    () -> errorLinesAbout(neo4j).size() == 1);
            long rowsAtFirstError = csvRows(dir);
            Await.until("five more intervals have passed", () -> csvRows(dir) >= rowsAtFirstError + 5);

            assertThat(errorLinesAbout(neo4j)).as("logged once per outage").hasSize(1);
            assertThat(errorLinesAbout(neo4j).get(0)).contains("127.0.0.1:" + port);
            assertThat(serves(db)).isTrue();
            assertThat(JmxQueries.value(db, OBJECT_NAME_PREFIX + HEAP_USED))
                    .as("the JMX exporter is unaffected")
                    .isNotNull();

            try (GraphiteListener graphite = GraphiteListener.listenOn(port)) {
                Await.until("metrics arrive once the server is up", () -> covers(graphite.lines(), HEAP_USED));
                Await.until(
                        "the recovery is logged", () -> infoLinesAbout(neo4j).size() == 1);
            }
            assertThat(errorLinesAbout(neo4j)).hasSize(1);

            Await.until(
                    "a new outage is logged again", () -> errorLinesAbout(neo4j).size() == 2);
            assertThat(infoLinesAbout(neo4j)).hasSize(1);
        }
    }

    private static boolean serves(GraphDatabaseService db) {
        try (var tx = db.beginTx()) {
            return tx.execute("RETURN 1 AS one").next().get("one").equals(1L);
        }
    }

    private static long csvRows(Path dir) {
        return Math.max(0, CsvExporterIT.lines(dir.resolve(HEAP_USED + ".csv")).size() - 1);
    }

    private static List<String> errorLinesAbout(Neo4j neo4j) {
        return logLines(neo4j, "ERROR");
    }

    private static List<String> infoLinesAbout(Neo4j neo4j) {
        return logLines(neo4j, "INFO");
    }

    private static List<String> logLines(Neo4j neo4j, String level) {
        Path debugLog = neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        try {
            return Files.readAllLines(debugLog).stream()
                    .filter(line -> line.contains(level) && line.contains("'" + GraphiteExporter.NAME + "'"))
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A gauge is one line named by the metric; a counter, histogram or timer adds Dropwizard's attribute suffixes. */
    private static boolean covers(List<String> lines, String metric) {
        return lines.stream()
                .map(GraphiteExporterIT::name)
                .anyMatch(name -> name.equals(metric) || name.startsWith(metric + "."));
    }

    private static String name(String line) {
        return line.split(" ")[0];
    }

    private static String value(String line) {
        return line.split(" ")[1];
    }

    private static long timestamp(String line) {
        return Long.parseLong(line.split(" ")[2]);
    }
}
