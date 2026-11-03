/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import java.nio.file.Path;
import java.util.List;
import org.dozerdb.metrics.settings.MetricsConfiguration;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.configuration.helpers.SocketAddress;

/**
 * Reads the metrics settings out of Neo4j's {@link Config} into a {@link MetricsConfiguration}. The same
 * {@link Config} is read by the running server and, offline, by {@code neo4j-admin} for the diagnostics report.
 */
public final class ConfigAdapter {

    private ConfigAdapter() {}

    /**
     * {@code server.directories.metrics}, resolved against {@code server.directories.neo4j_home} by Neo4j's own
     * setting dependency: the directory the CSV exporter writes to and the diagnostics report collects.
     */
    public static Path metricsDirectory(Config config) {
        return config.get(MetricsSettings.csv_path);
    }

    public static MetricsConfiguration read(Config config) {
        return new MetricsConfiguration(
                config.get(MetricsSettings.metrics_enabled),
                config.get(MetricsSettings.metrics_prefix),
                patterns(config.get(MetricsSettings.metrics_filter)),
                // The 5 settings reference has no server.metrics.deny_filter; the registry keeps its deny list,
                // always empty on this line, so that the registry stays the same across release lines.
                List.of(),
                new MetricsConfiguration.Csv(
                        config.get(MetricsSettings.csv_enabled),
                        metricsDirectory(config),
                        config.get(MetricsSettings.csv_interval),
                        config.get(MetricsSettings.csv_rotation_threshold),
                        config.get(MetricsSettings.csv_max_archives),
                        config.get(MetricsSettings.csv_archives_compression)),
                config.get(MetricsSettings.jmx_enabled),
                prometheus(config),
                graphite(config),
                config.get(MetricsInternalSettings.additional_collectors));
    }

    private static MetricsConfiguration.Prometheus prometheus(Config config) {
        SocketAddress endpoint = config.get(MetricsSettings.prometheus_endpoint);
        return new MetricsConfiguration.Prometheus(
                config.get(MetricsSettings.prometheus_enabled), endpoint.getHostname(), endpoint.getPort());
    }

    private static MetricsConfiguration.Graphite graphite(Config config) {
        SocketAddress server = config.get(MetricsSettings.graphite_server);
        return new MetricsConfiguration.Graphite(
                config.get(MetricsSettings.graphite_enabled),
                server.getHostname(),
                server.getPort(),
                config.get(MetricsSettings.graphite_interval));
    }

    private static List<String> patterns(List<GlobbingPattern> patterns) {
        return patterns.stream().map(GlobbingPattern::toString).toList();
    }
}
