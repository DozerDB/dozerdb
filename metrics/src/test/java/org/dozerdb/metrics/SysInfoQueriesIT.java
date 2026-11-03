/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.dozerdb.metrics.SysInfoQueries.Metric;
import org.dozerdb.metrics.inventory.InventoryEntry;
import org.dozerdb.metrics.inventory.MetricInventory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * {@code :sysinfo} shows live data: the queries cypher-shell and the Browser issue ({@link SysInfoQueries})
 * return a value for every metric they ask for that the inventory says DozerDB emits, with the default
 * settings (default prefix and filter, the way an administrator runs the server). On this line both clients ask only
 * for names the Operations Manual 5 documents and DozerDB emits ({@code store.size.total} included), so every metric
 * they ask for answers and {@link #NOT_YET} is empty; a fixture name the inventory does not emit would have to be
 * listed there.
 */
class SysInfoQueriesIT {

    private static final String PREFIX = "neo4j";
    private static final String DATABASE = "neo4j";

    /** The fixture metrics without a value: none, since every name the two clients ask for is emitted. */
    private static final Set<String> NOT_YET = Set.of();

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithDefaultSettings() {
        neo4j = MetricsServers.start(builder -> {});
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void cypherShellsSysinfoQueryReturnsAValueForEveryEmittedMetricItAsksFor() {
        List<Map<String, Object>> parameters = SysInfoQueries.CYPHER_SHELL.stream()
                .map(metric -> SysInfoQueries.cypherShellParameter(PREFIX, DATABASE, metric))
                .toList();

        Set<String> answered = new TreeSet<>();
        try (Transaction tx = db.beginTx()) {
            tx.execute(SysInfoQueries.CYPHER_SHELL_QUERY, Map.of("metrics", parameters)).stream()
                    .filter(row -> row.get("Value") != null)
                    .forEach(row -> answered.add((String) row.get("Name")));
        }

        assertThat(answered).containsExactlyInAnyOrderElementsOf(expectedAnswers(SysInfoQueries.CYPHER_SHELL));
    }

    @Test
    void theBrowsersSysinfoQueriesReturnAValueForEveryEmittedMetricTheyAskFor() {
        Set<String> answered = new TreeSet<>();
        for (Metric metric : SysInfoQueries.BROWSER) {
            try (Transaction tx = db.beginTx()) {
                tx.execute(SysInfoQueries.browserQuery(PREFIX, DATABASE, metric, "group")).stream()
                        .filter(row -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> attributes = (Map<String, Object>) row.get("attributes");
                            return attributes.get("Count") != null || attributes.get("Value") != null;
                        })
                        .forEach(row -> answered.add(metric.name()));
            }
        }

        assertThat(answered).containsExactlyInAnyOrderElementsOf(expectedAnswers(SysInfoQueries.BROWSER));
    }

    @Test
    void everyMetricTheTwoClientsAskForIsADocumentedMetricTheInventoryEmits() {
        Set<String> emitted = MetricInventory.load().emitted().stream()
                .map(InventoryEntry::metricName)
                .collect(Collectors.toSet());

        assertThat(NOT_YET).isEmpty();
        assertThat(SysInfoQueries.CYPHER_SHELL).extracting(Metric::name).allMatch(emitted::contains);
        assertThat(SysInfoQueries.BROWSER).extracting(Metric::name).allMatch(emitted::contains);
    }

    /** The fixture metrics the inventory emits: every name not listed as missing. */
    private static Set<String> expectedAnswers(List<Metric> fixture) {
        Set<String> emitted = MetricInventory.load().emitted().stream()
                .map(InventoryEntry::metricName)
                .collect(Collectors.toSet());
        Set<String> expected = new TreeSet<>();
        for (Metric metric : fixture) {
            if (emitted.contains(metric.name())) {
                assertThat(NOT_YET)
                        .as(metric.name() + " is emitted now; remove it from NOT_YET")
                        .doesNotContain(metric.name());
                expected.add(metric.name());
            } else {
                assertThat(NOT_YET)
                        .as(metric.name() + " is not emitted; list it in NOT_YET")
                        .contains(metric.name());
            }
        }
        return expected;
    }
}
