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
import static org.assertj.core.api.Assertions.entry;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.dozerdb.metrics.MetricsServers;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The metrics settings as an administrator sees them in {@code SHOW SETTINGS} on a running server: the same keys,
 * defaults and rendering as the Neo4j Operations Manual 5 configuration settings reference. The expected values
 * are copied from that reference, not derived from the code. How a value resolves against
 * {@code server.default_listen_address} is observed on servers started with those settings.
 */
class MetricsSettingsIT {

    /** Default of {@code server.metrics.filter} in the 5 settings reference, verbatim. */
    private static final String DOCUMENTED_DEFAULT_FILTER =
            "*bolt.connections*,*bolt.messages_received*,*bolt.messages_started*,*dbms.pool.bolt.free,"
                    + "*dbms.pool.bolt.total_size,*dbms.pool.bolt.total_used,*dbms.pool.bolt.used_heap,"
                    + "*cluster.raft.is_leader,*cluster.raft.last_leader_message,*cluster.raft.replication_attempt,"
                    + "*cluster.raft.replication_fail,*cluster.raft.last_applied,*cluster.raft.last_appended,"
                    + "*cluster.raft.append_index,*cluster.raft.commit_index,*cluster.raft.applied_index,"
                    + "*cluster.internal.discovery.memberset.left,*cluster.internal.discovery.crdt.gossip_id_data.size,"
                    + "*cluster.internal.discovery.crdt.server_data.size,"
                    + "*cluster.internal.discovery.crdt.database_data.size,"
                    + "*cluster.internal.discovery.crdt.leader_data.size,"
                    + "*cluster.internal.discovery.crdt.total_merge_operations,"
                    + "*cluster.internal.discovery.crdt.total_update_operations,"
                    + "*cluster.internal.discovery.gossip.incoming_queue_size,"
                    + "*cluster.internal.discovery.gossip.total_received_data,"
                    + "*cluster.internal.discovery.gossip.total_sent_data,"
                    + "*cluster.internal.discovery.gossip.uncontactable_members_exist,"
                    + "*check_point.*,*cypher.replan_events,*cypher.cache*,*ids_in_use*,"
                    + "*pool.transaction.*.total_used,*pool.transaction.*.used_heap,*pool.transaction.*.used_native,"
                    + "*store.size*,*transaction.active_read,*transaction.active_write,*transaction.committed*,"
                    + "*transaction.last_committed_tx_id,*transaction.peak_concurrent,*transaction.rollbacks*,"
                    + "*page_cache.hit*,*page_cache.page_faults,*page_cache.usage_ratio,*vm.file.descriptors.count,"
                    + "*vm.gc.time.*,*vm.heap.used,*vm.memory.buffer.direct.used,*vm.memory.pool.g1_eden_space,"
                    + "*vm.memory.pool.g1_old_gen,*vm.pause_time,*vm.thread*,*db.query.execution*,*protocol*";

    /**
     * Exactly the fifteen documented keys, with the documented defaults. The metrics directory defaults to
     * {@code metrics} under the Neo4j home. The internal test setting is not listed, and neither is
     * {@code server.metrics.deny_filter}, which the 5 reference does not have.
     */
    @Test
    void showSettingsListsTheFifteenDocumentedKeysWithTheirDocumentedDefaults() {
        try (Neo4j neo4j = MetricsServers.start(builder -> {})) {
            Path home = neo4j.config().get(GraphDatabaseSettings.neo4j_home);
            Map<String, String> values = metricsSettings(neo4j.defaultDatabaseService(), "value");

            assertThat(values)
                    .containsOnly(
                            entry("server.metrics.enabled", "true"),
                            entry("server.metrics.prefix", "neo4j"),
                            entry("server.metrics.filter", DOCUMENTED_DEFAULT_FILTER),
                            entry("server.metrics.csv.enabled", "true"),
                            entry(
                                    "server.directories.metrics",
                                    home.resolve("metrics").toString()),
                            entry("server.metrics.csv.interval", "30s"),
                            entry("server.metrics.csv.rotation.size", "10.00MiB"),
                            entry("server.metrics.csv.rotation.keep_number", "7"),
                            entry("server.metrics.csv.rotation.compression", "NONE"),
                            entry("server.metrics.jmx.enabled", "true"),
                            entry("server.metrics.graphite.enabled", "false"),
                            entry("server.metrics.graphite.server", "localhost:2003"),
                            entry("server.metrics.graphite.interval", "30s"),
                            entry("server.metrics.prometheus.enabled", "false"),
                            entry("server.metrics.prometheus.endpoint", "localhost:2004"));
            assertThat(showSettings(neo4j.defaultDatabaseService(), "internal.dozerdb.", "value"))
                    .as("the internal test setting is hidden")
                    .isEmpty();
        }
    }

    /**
     * The settings reference renders a declared default verbatim ({@code server.bolt.listen_address} shows
     * {@code :7687} because its default is port-only) and shows {@code localhost:2004} for the Prometheus endpoint, so
     * the declared default carries the host; the Graphite server's documented default is the port-only {@code :2003}.
     */
    @Test
    void showSettingsShowsTheDeclaredDefaultsOfTheTwoSocketAddresses() {
        try (Neo4j neo4j = MetricsServers.start(builder -> {})) {
            Map<String, String> defaults = metricsSettings(neo4j.defaultDatabaseService(), "defaultValue");

            assertThat(defaults)
                    .contains(
                            entry("server.metrics.prometheus.endpoint", "localhost:2004"),
                            entry("server.metrics.graphite.server", ":2003"));
        }
    }

    /**
     * Enabling the endpoint without a host must expose nothing by accident: where {@code server.default_listen_address}
     * is {@code 0.0.0.0}, as in the Docker image, a configured port-only {@code :2004} takes {@code localhost} from the
     * declared default, while the Graphite server, documented with the port-only default {@code :2003}, does follow
     * the listen address.
     */
    @Test
    void aPortOnlyPrometheusEndpointResolvesToLocalhostWhileGraphiteFollowsTheDefaultListenAddress() {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(
                        GraphDatabaseSettings.default_listen_address, new SocketAddress("0.0.0.0"))
                .withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress(2004)))) {
            Map<String, String> values = metricsSettings(neo4j.defaultDatabaseService(), "value");

            assertThat(values)
                    .contains(
                            entry("server.metrics.prometheus.endpoint", "localhost:2004"),
                            entry("server.metrics.graphite.server", "0.0.0.0:2003"));
        }
    }

    /** Binding all interfaces is an explicit choice, as the manual's Kubernetes example makes it. */
    @Test
    void aConfiguredWildcardPrometheusEndpointIsKept() {
        try (Neo4j neo4j = MetricsServers.start(builder ->
                builder.withConfig(MetricsSettings.prometheus_endpoint, new SocketAddress("0.0.0.0", 2004)))) {
            assertThat(metricsSettings(neo4j.defaultDatabaseService(), "value"))
                    .containsEntry("server.metrics.prometheus.endpoint", "0.0.0.0:2004");
        }
    }

    private static Map<String, String> metricsSettings(GraphDatabaseService db, String column) {
        Map<String, String> settings = new TreeMap<>(showSettings(db, "server.metrics.", column));
        settings.putAll(showSettings(db, "server.directories.metrics", column));
        return settings;
    }

    private static Map<String, String> showSettings(GraphDatabaseService db, String namePrefix, String column) {
        Map<String, String> settings = new TreeMap<>();
        try (Transaction tx = db.beginTx()) {
            tx.execute(
                            "SHOW SETTINGS YIELD name, " + column + " WHERE name STARTS WITH $prefix RETURN name, "
                                    + column + " AS shown",
                            Map.of("prefix", namePrefix))
                    .forEachRemaining(row -> settings.put((String) row.get("name"), String.valueOf(row.get("shown"))));
        }
        return settings;
    }
}
