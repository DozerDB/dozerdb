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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.PrometheusScrapes;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;
import org.dozerdb.metrics.inventory.JmxMetricTypes;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * With {@code server.metrics.prometheus.enabled=true} a Prometheus server scrapes {@code /metrics} on the
 * configured endpoint and finds every registered metric in the text format, named and typed as Neo4j's endpoint
 * names and types them, as public scrapes and dashboards show: dots and disallowed characters become underscores,
 * nothing is prefixed, a counter is a {@code counter} family ending in {@code _total}, a histogram a
 * {@code summary}. The registered metrics are enumerated through {@code dbms.queryJmx}, never through the
 * registry, so the scrape is checked against what an administrator can see.
 */
class PrometheusEndpointIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=";
    private static final List<String> QUANTILES = List.of("0.5", "0.75", "0.95", "0.98", "0.99", "0.999");
    private static int port;
    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithTheEndpointOnAFreePort() throws IOException {
        port = PrometheusScrapes.freePort();
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.prometheus_enabled, true)
                        .withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("localhost", port)));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void everyRegisteredMetricIsExposedWithItsNameTypeAndASampleAndNothingElse() throws Exception {
        HttpResponse<String> response = PrometheusScrapes.get("localhost", port, PrometheusScrapes.PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/plain; version=0.0.4; charset=utf-8");
        String text = response.body();
        Map<String, MetricType> registered = registeredMetrics();
        assertThat(registered).isNotEmpty();
        Set<String> expectedFamilies = new TreeSet<>();
        registered.forEach((fullName, type) -> {
            String name = fullName.replaceAll("[^A-Za-z0-9:]", "_");
            String family = type == MetricType.COUNTER ? name + "_total" : name;
            expectedFamilies.add(family);
            assertThat(text)
                    .contains("# HELP " + family + " Generated from Dropwizard metric import (metric=" + fullName
                            + ", type=");
            switch (type) {
                case GAUGE -> {
                    assertThat(text).contains("# TYPE " + family + " gauge\n");
                    assertSample(text, name);
                }
                case COUNTER -> {
                    assertThat(text).contains("# TYPE " + family + " counter\n");
                    assertSample(text, family);
                }
                case HISTOGRAM, TIMER -> {
                    assertThat(text).contains("# TYPE " + family + " summary\n");
                    for (String quantile : QUANTILES) {
                        assertSample(text, name + "{quantile=\"" + quantile + "\",}");
                    }
                    assertSample(text, name + "_count");
                    assertThat(text).doesNotContain(name + "_sum");
                }
            }
        });
        assertThat(PrometheusScrapes.families(text))
                .as("the exposition holds exactly the registered metrics")
                .containsExactlyInAnyOrderElementsOf(expectedFamilies);
    }

    @Test
    void pathsOtherThanMetricsAreNotFound() throws Exception {
        assertThat(PrometheusScrapes.get("localhost", port, "/").statusCode()).isEqualTo(404);
        assertThat(PrometheusScrapes.get("localhost", port, "/metrics/").statusCode())
                .isEqualTo(404);
        assertThat(PrometheusScrapes.get("localhost", port, "/-/healthy").statusCode())
                .isEqualTo(404);
    }

    /**
     * The exporter serves on the JDK HTTP server (its dispatcher thread is {@code HTTP-Dispatcher}) and renders
     * requests on its own named thread, the way the CSV exporter's writes run on {@code neo4j.MetricsCsvWrite-N}:
     * no database thread is involved, which a thread dump shows an administrator.
     */
    @Test
    void theEndpointServesOnTheJdkHttpServerOffDatabaseThreads() throws Exception {
        PrometheusScrapes.scrape("localhost", port);

        assertThat(threadNames()).contains(PrometheusExporter.THREAD_NAME, "HTTP-Dispatcher");
    }

    @Test
    void theEndpointIsDisabledByDefault() throws Exception {
        int unusedPort = PrometheusScrapes.freePort();
        try (Neo4j defaults = MetricsServers.start(builder ->
                builder.withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("localhost", unusedPort)))) {
            assertThat(defaults.config().get(MetricsSettings.prometheus_enabled))
                    .isFalse();
            assertThat(PrometheusScrapes.refusesConnections("localhost", unusedPort))
                    .isTrue();
        }
    }

    /**
     * {@code :port} alone takes its host from the declared default {@code localhost:2004}, not from
     * {@code server.default_listen_address}: Neo4j fills a configured value's missing parts from the declared
     * default first and only then from the dependency ({@code Config.evaluateSetting}), and the manual documents
     * {@code localhost:2004} as the default. The default listen address is set to the IPv6 loopback so that the
     * two candidates are distinguishable, {@code localhost} being IPv4 to Java: the scrape succeeds on
     * {@code 127.0.0.1} and is refused on {@code [::1]}. The port is free rather than 2004, which another process
     * may hold on the build machine.
     */
    @Test
    void aPortOnlyEndpointBindsLocalhostNotTheDefaultListenAddress() throws Exception {
        assumeTrue(ipv6LoopbackIsConfigured(), "no IPv6 loopback on this machine");
        int portOnly = PrometheusScrapes.freePort();
        try (Neo4j listening = MetricsServers.start(
                builder -> builder.withConfig(GraphDatabaseSettings.default_listen_address, new SocketAddress("::1"))
                        .withConfig(MetricsSettings.prometheus_enabled, true)
                        .withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress(portOnly)))) {
            assertThat(listening.config().get(MetricsSettings.prometheus_endpoint))
                    .isEqualTo(new SocketAddress("localhost", portOnly));
            assertThat(PrometheusScrapes.scrape("127.0.0.1", portOnly))
                    .contains("# TYPE neo4j_dbms_vm_heap_used gauge\n");
            assertThat(PrometheusScrapes.refusesConnections("::1", portOnly)).isTrue();
        }
    }

    /**
     * A port already in use is an exporter failure, isolated like any metrics failure: logged once at error level and disabling the
     * exporter while the server keeps serving Cypher and the other exporters.
     */
    @Test
    void aPortAlreadyInUseDisablesTheExporterWithOneErrorLineWhileTheServerServes() throws Exception {
        // Bound to localhost exactly: a wildcard holder would let a second socket bind 127.0.0.1 on this platform.
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            int busyPort = holder.getLocalPort();
            try (Neo4j blocked = MetricsServers.start(builder -> builder.withConfig(
                            MetricsSettings.prometheus_enabled, true)
                    .withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("localhost", busyPort)))) {
                GraphDatabaseService blockedDb = blocked.defaultDatabaseService();
                try (var tx = blockedDb.beginTx()) {
                    assertThat(tx.execute("RETURN 1 AS one").next()).containsEntry("one", 1L);
                }
                assertThat(JmxQueries.value(blockedDb, "neo4j.metrics:name=neo4j.dbms.vm.heap.used"))
                        .as("the JMX exporter is unaffected")
                        .isNotNull();
                assertThat(errorLinesAbout(blocked, PrometheusExporter.NAME)).hasSize(1);
                assertThat(holder.isBound())
                        .as("the port stays with its holder")
                        .isTrue();
            }
        }
    }

    /** The class-level server keeps its own endpoint thread, so the second server's thread is counted, not named. */
    @Test
    void stoppingTheServerReleasesThePortAndTheThread() throws Exception {
        int ownPort = PrometheusScrapes.freePort();
        long threadsBefore = threadsNamed(PrometheusExporter.THREAD_NAME);
        Neo4j stopped = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.prometheus_enabled, true)
                .withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("localhost", ownPort)));
        PrometheusScrapes.scrape("localhost", ownPort);
        assertThat(threadsNamed(PrometheusExporter.THREAD_NAME)).isEqualTo(threadsBefore + 1);

        stopped.close();

        try (ServerSocket rebound = new ServerSocket(ownPort, 1, InetAddress.getByName("localhost"))) {
            assertThat(rebound.isBound()).as("the port is released").isTrue();
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (threadsNamed(PrometheusExporter.THREAD_NAME) > threadsBefore
                && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(threadsNamed(PrometheusExporter.THREAD_NAME))
                .as("the request thread has ended")
                .isEqualTo(threadsBefore);
    }

    private static Set<String> threadNames() {
        Set<String> names = new TreeSet<>();
        Thread.getAllStackTraces().keySet().forEach(thread -> names.add(thread.getName()));
        return names;
    }

    private static long threadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals(name))
                .count();
    }

    private static boolean ipv6LoopbackIsConfigured() throws IOException {
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet6Address && address.isLoopbackAddress()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> errorLinesAbout(Neo4j server, String component) throws IOException {
        Path debugLog =
                server.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        return Files.readAllLines(debugLog).stream()
                .filter(line -> line.contains("ERROR") && line.contains("'" + component + "'"))
                .toList();
    }

    /** One sample line {@code <name> <number>}, no labels but a summary's quantile and no timestamp. */
    private static void assertSample(String text, String sampleName) {
        Pattern sample = Pattern.compile(
                "^" + Pattern.quote(sampleName) + " (-?[0-9]+(\\.[0-9]+)?(E-?[0-9]+)?|[+-]Inf|NaN)$",
                Pattern.MULTILINE);
        assertThat(text)
                .as("a sample of " + sampleName)
                .matches(t -> sample.matcher(t).find());
    }

    /** Every metric in the JMX domain by full name with the type its MBean's attributes show. */
    private static Map<String, MetricType> registeredMetrics() {
        Map<String, MetricType> registered = new TreeMap<>();
        JmxQueries.attributeNames(db, OBJECT_NAME_PREFIX + "*").forEach((objectName, attributes) -> {
            String name = objectName.substring(OBJECT_NAME_PREFIX.length());
            registered.put(
                    name.startsWith("\"") ? ObjectName.unquote(name) : name,
                    JmxMetricTypes.fromAttributes(attributes)
                            .orElseThrow(() -> new AssertionError(name + " has no inventory type: " + attributes)));
        });
        return registered;
    }
}
