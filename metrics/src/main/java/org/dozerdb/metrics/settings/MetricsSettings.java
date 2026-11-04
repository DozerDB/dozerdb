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

import static org.neo4j.configuration.GraphDatabaseSettings.default_listen_address;
import static org.neo4j.configuration.GraphDatabaseSettings.neo4j_home;
import static org.neo4j.configuration.SettingConstraints.min;
import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.BOOL;
import static org.neo4j.configuration.SettingValueParsers.BYTES;
import static org.neo4j.configuration.SettingValueParsers.DURATION;
import static org.neo4j.configuration.SettingValueParsers.GLOBBING_PATTERN;
import static org.neo4j.configuration.SettingValueParsers.INT;
import static org.neo4j.configuration.SettingValueParsers.PATH;
import static org.neo4j.configuration.SettingValueParsers.SOCKET_ADDRESS;
import static org.neo4j.configuration.SettingValueParsers.STRING;
import static org.neo4j.configuration.SettingValueParsers.listOf;
import static org.neo4j.configuration.SettingValueParsers.ofEnum;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.neo4j.annotations.api.PublicApi;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.Description;
import org.neo4j.configuration.SettingsDeclaration;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.config.Setting;

/**
 * Declares every {@code server.metrics.*} setting and {@code server.directories.metrics} with Neo4j's names,
 * defaults, types and constraints (Operations Manual 2026.10, configuration settings reference), so that a
 * {@code neo4j.conf} written for Neo4j Enterprise starts DozerDB under strict settings validation.
 *
 * <p>Invalid values fail startup through Neo4j's own validation; this is the one kind of metrics failure that
 * is not isolated, as in Neo4j.
 */
@ServiceProvider
@PublicApi
public final class MetricsSettings implements SettingsDeclaration {

    /** Default of {@code server.metrics.filter} in the 2026.10 settings reference, verbatim. */
    private static final String DEFAULT_FILTER =
            "*bolt.connections*,*bolt.messages_received*,*bolt.messages_started*,*dbms.pool.bolt.free,"
                    + "*dbms.pool.bolt.total_size,*dbms.pool.bolt.total_used,*dbms.pool.bolt.used_heap,"
                    + "*cluster.raft.is_leader,*cluster.raft.last_leader_message,*cluster.raft.replication_attempt,"
                    + "*cluster.raft.replication_fail,*cluster.raft.last_applied,*cluster.raft.last_appended,"
                    + "*cluster.raft.append_index,*cluster.raft.commit_index,*cluster.raft.applied_index,"
                    + "*check_point.*,*cypher.replan_events,*cypher.cache*,*ids_in_use*,*.neo4j.count.*,"
                    + "*pool.transaction.*.total_used,*pool.transaction.*.used_heap,*pool.transaction.*.used_native,"
                    + "*store.size*,*transaction.active_read,*transaction.active_write,*transaction.committed*,"
                    + "*transaction.last_committed_tx_id,*transaction.peak_concurrent,*transaction.rollbacks*,"
                    + "*page_cache.hit*,*page_cache.page_faults,*page_cache.usage_ratio,*vm.file.descriptors.count,"
                    + "*vm.gc.time.*,*vm.heap.used,*vm.memory.buffer.direct.used,*vm.memory.pool.g1_eden_space,"
                    + "*vm.memory.pool.g1_old_gen,*vm.pause_time,*vm.thread*,*db.query.execution*,*protocol*";

    /** Compression of rotated CSV history files, as the settings reference lists them. */
    public enum CompressionOption {
        NONE,
        ZIP,
        GZ
    }

    @Description("Enable metrics. Setting this to false turns off all metrics.")
    public static final Setting<Boolean> metrics_enabled =
            newBuilder("server.metrics.enabled", BOOL, true).build();

    @Description("A common prefix for the reported metrics field names.")
    public static final Setting<String> metrics_prefix =
            newBuilder("server.metrics.prefix", STRING, "neo4j").build();

    @Description("Specifies which metrics should be enabled by using a comma separated list of globbing patterns. "
            + "Only the metrics matching the filter will be enabled.")
    public static final Setting<List<GlobbingPattern>> metrics_filter = newBuilder(
                    "server.metrics.filter",
                    listOf(GLOBBING_PATTERN),
                    listOf(GLOBBING_PATTERN).parse(DEFAULT_FILTER))
            .build();

    @Description("Specifies which metrics should be disabled by using a comma-separated list of globbing patterns. "
            + "Overrides the enabled metrics of server.metrics.filter.")
    public static final Setting<List<GlobbingPattern>> metrics_deny_filter = newBuilder(
                    "server.metrics.deny_filter", listOf(GLOBBING_PATTERN), List.of())
            .build();

    @Description("Set to true to enable exporting metrics to CSV files.")
    public static final Setting<Boolean> csv_enabled =
            newBuilder("server.metrics.csv.enabled", BOOL, true).build();

    @Description("The target location of the CSV files: a path to a directory wherein a CSV file per reported "
            + "field will be written.")
    public static final Setting<Path> csv_path = newBuilder("server.directories.metrics", PATH, Path.of("metrics"))
            .setDependency(neo4j_home)
            .immutable()
            .build();

    @Description("The reporting interval for the CSV files. That is, how often new rows with numbers are appended "
            + "to the CSV files.")
    public static final Setting<Duration> csv_interval = newBuilder(
                    "server.metrics.csv.interval", DURATION, Duration.ofSeconds(30))
            .addConstraint(min(Duration.ofMillis(1)))
            .build();

    @Description("The file size in bytes at which the CSV files will auto-rotate. If set to zero then no rotation "
            + "will occur. Accepts a binary suffix k, m or g.")
    public static final Setting<Long> csv_rotation_threshold = newBuilder(
                    "server.metrics.csv.rotation.size", BYTES, 10L * 1024 * 1024)
            .build();

    @Description("Maximum number of history files for the CSV files.")
    public static final Setting<Integer> csv_max_archives = newBuilder(
                    "server.metrics.csv.rotation.keep_number", INT, 7)
            .addConstraint(min(1))
            .build();

    @Description("Decides what compression to use for the CSV history files.")
    public static final Setting<CompressionOption> csv_archives_compression = newBuilder(
                    "server.metrics.csv.rotation.compression", ofEnum(CompressionOption.class), CompressionOption.ZIP)
            .build();

    @Description("Set to true to enable the JMX metrics endpoint.")
    public static final Setting<Boolean> jmx_enabled =
            newBuilder("server.metrics.jmx.enabled", BOOL, true).build();

    @Description("Set to true to enable exporting metrics to Graphite.")
    public static final Setting<Boolean> graphite_enabled =
            newBuilder("server.metrics.graphite.enabled", BOOL, false).build();

    @Description("The hostname or IP address of the Graphite server. A missing hostname is taken from "
            + "server.default_listen_address.")
    public static final Setting<SocketAddress> graphite_server = newBuilder(
                    "server.metrics.graphite.server", SOCKET_ADDRESS, new SocketAddress(2003))
            .setDependency(default_listen_address)
            .build();

    @Description("The reporting interval for Graphite. That is, how often to send updated metrics to Graphite.")
    public static final Setting<Duration> graphite_interval = newBuilder(
                    "server.metrics.graphite.interval", DURATION, Duration.ofSeconds(30))
            .build();

    @Description("Set to true to enable the Prometheus endpoint.")
    public static final Setting<Boolean> prometheus_enabled =
            newBuilder("server.metrics.prometheus.enabled", BOOL, false).build();

    /**
     * The settings reference shows this default as {@code localhost:2004} (a port-only default would render as
     * {@code :2004}, as {@code server.bolt.listen_address} does), so the declared default carries the host. Neo4j
     * fills a configured value's missing parts from the declared default before the dependency
     * ({@code Config.evaluateSetting}: {@code solveDefault}, then {@code createEntry}), so {@code :2004} resolves to
     * {@code localhost:2004} and the endpoint never follows {@code server.default_listen_address} by accident: in
     * the Docker image, where that is {@code 0.0.0.0}, enabling the endpoint exposes nothing until
     * {@code 0.0.0.0:2004} is configured, as the manual's Kubernetes example does. The dependency is kept as Neo4j
     * declares it; it fills a part only when the declared default lacks it.
     */
    @Description("The hostname and port to use as Prometheus endpoint. A missing part is taken from the default, "
            + "localhost:2004.")
    public static final Setting<SocketAddress> prometheus_endpoint = newBuilder(
                    "server.metrics.prometheus.endpoint", SOCKET_ADDRESS, new SocketAddress("localhost", 2004))
            .setDependency(default_listen_address)
            .build();
}
