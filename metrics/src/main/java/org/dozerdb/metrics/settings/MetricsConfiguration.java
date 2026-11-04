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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The metrics settings of one server as plain values, read once at extension start. The upstream adapter builds
 * it from Neo4j's configuration so that no other package has to touch Neo4j's {@code Config}.
 *
 * @param enabled {@code server.metrics.enabled}, the master switch
 * @param prefix {@code server.metrics.prefix}, the leading segment of every metric name and of the JMX domain
 * @param filter {@code server.metrics.filter} as glob patterns over full metric names
 * @param denyFilter {@code server.metrics.deny_filter} as glob patterns over full metric names
 * @param csv the CSV exporter's settings
 * @param jmxEnabled {@code server.metrics.jmx.enabled}
 * @param prometheus the Prometheus endpoint's settings
 * @param graphite the Graphite exporter's settings
 * @param additionalCollectors {@code internal.dozerdb.metrics.additional_collectors}, the test-only seam
 */
public record MetricsConfiguration(
        boolean enabled,
        String prefix,
        List<String> filter,
        List<String> denyFilter,
        Csv csv,
        boolean jmxEnabled,
        Prometheus prometheus,
        Graphite graphite,
        List<String> additionalCollectors) {

    /**
     * The CSV exporter's settings.
     *
     * @param enabled {@code server.metrics.csv.enabled}
     * @param directory {@code server.directories.metrics}, already resolved against the Neo4j home
     * @param interval {@code server.metrics.csv.interval}
     * @param rotationSize {@code server.metrics.csv.rotation.size} in bytes; zero disables rotation
     * @param keepNumber {@code server.metrics.csv.rotation.keep_number}, the archives kept per metric
     * @param compression {@code server.metrics.csv.rotation.compression} of the archives
     */
    public record Csv(
            boolean enabled,
            Path directory,
            Duration interval,
            long rotationSize,
            int keepNumber,
            MetricsSettings.CompressionOption compression) {}

    /**
     * The Prometheus endpoint's settings.
     *
     * @param enabled {@code server.metrics.prometheus.enabled}
     * @param host the host of {@code server.metrics.prometheus.endpoint}, {@code localhost} from the declared
     *     default when the setting names none
     * @param port the port of {@code server.metrics.prometheus.endpoint}
     */
    public record Prometheus(boolean enabled, String host, int port) {}

    /**
     * The Graphite exporter's settings.
     *
     * @param enabled {@code server.metrics.graphite.enabled}
     * @param host the host of {@code server.metrics.graphite.server}, already taken from
     *     {@code server.default_listen_address} when the setting names none
     * @param port the port of {@code server.metrics.graphite.server}
     * @param interval {@code server.metrics.graphite.interval}
     */
    public record Graphite(boolean enabled, String host, int port, Duration interval) {}
}
