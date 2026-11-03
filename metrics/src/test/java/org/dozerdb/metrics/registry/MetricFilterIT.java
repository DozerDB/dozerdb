/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.harness.Neo4j;

/**
 * {@code server.metrics.filter} is a comma-separated list of globs with {@code *} and {@code ?}, matched against
 * full metric names (the Operations Manual 5 has no {@code server.metrics.deny_filter}; {@code SettingsValidationIT}
 * shows that it fails strict validation). A metric the filter excludes is never registered, so it
 * appears in no exporter. The three heap gauges are the metrics under test; the default filter admits only
 * {@code *vm.heap.used} of them.
 */
class MetricFilterIT {

    private static final String DOMAIN = "neo4j.metrics:*";
    private static final String COMMITTED = "neo4j.metrics:name=neo4j.dbms.vm.heap.committed";
    private static final String USED = "neo4j.metrics:name=neo4j.dbms.vm.heap.used";
    private static final String MAX = "neo4j.metrics:name=neo4j.dbms.vm.heap.max";

    @Test
    void theDefaultFilterAdmitsOnlyHeapUsedOfTheHeapGauges() {
        try (Neo4j neo4j = MetricsServers.start(builder -> {})) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:name=*vm.heap*"))
                    .containsExactly(USED);
        }
    }

    /**
     * Neo4j's default filter names {@code *cypher.replan_events} and {@code *cypher.cache*}; the documented cache
     * metrics are spelled {@code cypher_cache_<kind>_...} and so match neither. DozerDB keeps the documented names and
     * the default filter as Neo4j ships it, so by default only the replan counter of the Cypher group is enabled.
     */
    @Test
    void theDefaultFilterAdmitsOnlyReplanEventsOfTheCypherMetrics() {
        try (Neo4j neo4j = MetricsServers.start(builder -> {})) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:name=*cypher*"))
                    .containsExactly(
                            "neo4j.metrics:name=neo4j.database.neo4j.cypher.replan_events",
                            "neo4j.metrics:name=neo4j.database.system.cypher.replan_events");
        }
    }

    @Test
    void starMatchesAnySequenceOfCharacters() {
        try (Neo4j neo4j = MetricsServers.start(filter("*"))) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), DOMAIN)).contains(COMMITTED, USED, MAX);
        }
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        try (Neo4j neo4j = MetricsServers.start(filter("*vm.heap.m?x", "*vm.heap.u?ed", "*vm.heap.committe?x"))) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), DOMAIN)).containsExactly(MAX, USED);
        }
    }

    @Test
    void anExcludedMetricIsNeverRegistered() {
        try (Neo4j neo4j = MetricsServers.start(filter("*vm.heap.used"))) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), DOMAIN)).containsExactly(USED);
        }
    }

    @Test
    void anEmptyFilterAdmitsNothing() {
        try (Neo4j neo4j = MetricsServers.start(filter())) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), DOMAIN)).isEmpty();
        }
    }

    private static java.util.function.Consumer<org.neo4j.harness.Neo4jBuilder> filter(String... patterns) {
        return builder -> builder.withConfig(MetricsSettings.metrics_filter, patterns(patterns));
    }

    private static List<GlobbingPattern> patterns(String... patterns) {
        return GlobbingPattern.create(patterns);
    }
}
