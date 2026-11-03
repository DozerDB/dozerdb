/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.dozerdb.metrics.PrometheusScrapes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;

/**
 * A {@code neo4j.conf} written for Neo4j must start DozerDB. The fragment below is copied verbatim from the
 * configuration examples on the Operations Manual 5 pages "Enable metrics logging" and "Expose metrics",
 * including the placeholder directory and the exporters that are enabled there. Strict settings validation is on
 * by default, so an undeclared key or an invalid value would fail the configuration build.
 *
 * <p>The placeholder {@code server.directories.metrics=/local/file/system/path} is kept verbatim. On the usual
 * test machine it cannot be created, so the CSV exporter is disabled with one error line while the server serves;
 * where it can be created (a root user), the exporter writes there and no error is logged.
 *
 * <p>The Graphite lines are kept verbatim as well: {@code server.metrics.graphite.enabled=true} against
 * {@code localhost:2003}, where nothing listens on the test machine. The exporter's first report is one
 * {@code server.metrics.graphite.interval} (30 s) after start, so this server stops before a connection is
 * attempted; were one attempted, the unreachable server is logged once and retried at the next report, never
 * fatal.
 */
class Neo4jConfFragmentIT {

    /** Operations Manual 5, monitoring/metrics/enable, the one configuration example. */
    private static final String ENABLE_PAGE =
            """
            # Setting for enabling all supported metrics. (Default is true) Setting this to false disables all metrics.
            server.metrics.enabled=true

            # Setting for enabling only the metrics matching the filter.
            server.metrics.filter=*check_point*,neo4j.page_cache.evictions
            """;

    /** Operations Manual 5, monitoring/metrics/expose, the CSV, JMX, Graphite and Prometheus examples. */
    private static final String EXPOSE_PAGE =
            """
            # Enable the CSV exporter. Default is true.
            server.metrics.csv.enabled=true
            # Directory path for output files.
            # Default is the /metrics directory under NEO4J_HOME.
            server.directories.metrics=/local/file/system/path
            # How often to store data. Default is 30 seconds.
            server.metrics.csv.interval=30s
            # The maximum number of CSV files that will be saved. Default is 7.
            server.metrics.csv.rotation.keep_number=7
            # The file size at which the CSV files will auto-rotate. Default is 10.00MiB.
            server.metrics.csv.rotation.size=10.00MiB
            # Compresses the metric archive files. Default is NONE. Possible values are NONE, ZIP, and GZ.
            server.metrics.csv.rotation.compression=ZIP

            # Enable the JMX MBeans integration. Default is true.
            server.metrics.jmx.enabled=true

            # Enable the Graphite integration. Default is false.
            server.metrics.graphite.enabled=true
            # The hostname or IP address of the Graphite server.
            # A socket address in the format <hostname>, <hostname>:<port>, or :<port>.
            # If missing, the port or hostname is acquired from server.default_listen_address.
            # The default port number for Graphite is 2003.
            server.metrics.graphite.server=localhost:2003
            # How often to send data. Default is 30 seconds.
            server.metrics.graphite.interval=30s
            # Prefix for Neo4j metrics on Graphite server.
            server.metrics.prefix=neo4j

            # Enable the Prometheus endpoint. Default is false.
            server.metrics.prometheus.enabled=true
            # The hostname and port to use as Prometheus endpoint.
            # A socket address is in the format <hostname>, <hostname>:<port>, or :<port>.
            # If missing, the port or hostname is acquired from server.default_listen_address.
            # The default is localhost:2004.
            server.metrics.prometheus.endpoint=localhost:2004
            """;

    @Test
    void theManualsConfigurationLinesPassStrictValidation(@TempDir Path dir) throws IOException {
        Config config = Config.newBuilder().fromFile(writeFragment(dir)).build();

        assertThat(config.get(MetricsSettings.metrics_filter))
                .containsExactly(
                        new GlobbingPattern("*check_point*"), new GlobbingPattern("neo4j.page_cache.evictions"));
        assertThat(config.get(MetricsSettings.csv_path)).isEqualTo(Path.of("/local/file/system/path"));
        assertThat(config.get(MetricsSettings.csv_rotation_threshold)).isEqualTo(10L * 1024 * 1024);
        assertThat(config.get(MetricsSettings.csv_archives_compression))
                .isEqualTo(MetricsSettings.CompressionOption.ZIP);
        assertThat(config.get(MetricsSettings.graphite_interval)).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.get(MetricsSettings.prometheus_enabled)).isTrue();
    }

    /**
     * The server starts with every line of the manual's fragment except the Prometheus endpoint's port: the
     * documented {@code localhost:2004} is replaced by a free port, because a test never binds a port that belongs to
     * the machine (a running Neo4j, a monitoring stack), and whether the documented port is free cannot be probed
     * reliably (on macOS a process can bind {@code 127.0.0.1:2004} while another holds the IPv6 wildcard, so a probe
     * that connects proves nothing). {@link #theManualsConfigurationLinesPassStrictValidation} still validates the
     * verbatim lines, and the endpoint is scraped on the port the server bound.
     */
    @Test
    void aServerStartsWithTheManualsConfigurationLines(@TempDir Path dir) throws Exception {
        Config config = Config.newBuilder().fromFile(writeFragment(dir)).build();
        Neo4jBuilder builder =
                DozerDbBuilders.newInProcessBuilder().withDisabledServer().withConfig(BoltConnector.enabled, false);
        for (String key : configuredKeys()) {
            Setting<Object> setting = config.getSetting(key);
            builder.withConfig(setting, config.get(setting));
        }
        int port = PrometheusScrapes.freePort();
        builder.withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("localhost", port));

        try (Neo4j neo4j = builder.build()) {
            try (var tx = neo4j.defaultDatabaseService().beginTx()) {
                assertThat(tx.execute("RETURN 1 AS one").next()).containsEntry("one", 1L);
            }
            assertThat(neo4j.config().get(MetricsSettings.prometheus_enabled)).isTrue();
            HttpResponse<String> scrape = PrometheusScrapes.get("localhost", port, PrometheusScrapes.PATH);
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.headers().firstValue("Content-Type"))
                    .contains("text/plain; version=0.0.4; charset=utf-8");
            Path placeholder = Path.of("/local/file/system/path");
            if (!Files.isDirectory(placeholder)) {
                assertThat(errorLinesAbout(neo4j, "csv"))
                        .as("the uncreatable placeholder directory disables the CSV exporter with one error line")
                        .hasSize(1);
            }
        }
    }

    private static List<String> errorLinesAbout(Neo4j neo4j, String component) throws IOException {
        Path debugLog = neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        return Files.readAllLines(debugLog).stream()
                .filter(line -> line.contains("ERROR") && line.contains("'" + component + "'"))
                .toList();
    }

    private static Path writeFragment(Path dir) throws IOException {
        return Files.writeString(dir.resolve("neo4j.conf"), ENABLE_PAGE + "\n" + EXPOSE_PAGE);
    }

    private static Iterable<String> configuredKeys() {
        return (ENABLE_PAGE + EXPOSE_PAGE)
                .lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.substring(0, line.indexOf('=')))
                .toList();
    }
}
