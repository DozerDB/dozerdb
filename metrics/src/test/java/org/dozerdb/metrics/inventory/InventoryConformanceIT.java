/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.exporters.JmxExporter;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The registry may contain only metrics the inventory tiers Compatible or Partial, must contain all of them, and
 * each with the attribute shape of its documented type: with an open filter, the {@code <prefix>.metrics} JMX
 * domain equals the inventory's emitted rows expanded against this server. {@code <prefix>} is the configured
 * prefix, {@code <db>} every database the server hosts (listed through {@code SHOW DATABASES}, so a user
 * database counts too), and {@code <pool>}, {@code <gc>} and {@code <bufferpool>} any instance the JVM provides.
 *
 * <p>A collector that starts emitting a row changes nothing here: the failure message names every extra, missing or
 * mistyped metric.
 */
class InventoryConformanceIT {

    private static final String PREFIX = MetricsSettings.metrics_prefix.defaultValue();
    private static final String OBJECT_NAME_PREFIX = JmxExporter.domain(PREFIX) + ":name=";

    @Test
    void theJmxDomainWithAnOpenFilterEqualsTheInventorysEmittedMetrics() {
        try (Neo4j neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            GraphDatabaseService system = neo4j.databaseManagementService().database("system");
            execute(system, "CREATE DATABASE conformance");
            List<String> databases = awaitOnline(system, "conformance");

            List<ExpectedMetric> expected =
                    ExpectedMetric.expand(MetricInventory.load().emitted(), PREFIX, databases);
            InventoryConformance.Report report =
                    InventoryConformance.compare(expected, registeredMetrics(neo4j.defaultDatabaseService()));

            assertThat(report.problems())
                    .as("the JMX domain disagrees with the inventory")
                    .isEmpty();
        }
    }

    /** The online databases once the named one is among them; DozerDB does not accept {@code CREATE DATABASE ... WAIT}. */
    private static List<String> awaitOnline(GraphDatabaseService system, String database) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        List<String> online = onlineDatabases(system);
        while (!online.contains(database)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(database + " did not come online; online databases: " + online);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            online = onlineDatabases(system);
        }
        return online;
    }

    private static List<String> onlineDatabases(GraphDatabaseService system) {
        try (Transaction tx = system.beginTx()) {
            return tx
                    .execute("SHOW DATABASES YIELD name, currentStatus WHERE currentStatus = 'online' "
                            + "RETURN DISTINCT name ORDER BY name")
                    .stream()
                    .map(row -> (String) row.get("name"))
                    .toList();
        }
    }

    /** Every metric in the JMX domain by full metric name, with the attribute names of its MBean. */
    private static Map<String, Set<String>> registeredMetrics(GraphDatabaseService db) {
        Map<String, Set<String>> registered = new TreeMap<>();
        JmxQueries.attributeNames(db, OBJECT_NAME_PREFIX + "*").forEach((objectName, attributes) -> {
            String name = objectName.substring(OBJECT_NAME_PREFIX.length());
            registered.put(name.startsWith("\"") ? ObjectName.unquote(name) : name, attributes);
        });
        return registered;
    }

    private static void execute(GraphDatabaseService db, String cypher) {
        try (Transaction tx = db.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }
}
