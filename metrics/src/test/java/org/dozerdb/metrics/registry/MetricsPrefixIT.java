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

import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;

/** {@code server.metrics.prefix} is the leading segment of every metric name and of the JMX domain. */
class MetricsPrefixIT {

    @Test
    void thePrefixChangesMetricNamesAndTheJmxDomain() {
        try (Neo4j neo4j =
                MetricsServers.start(builder -> builder.withConfig(MetricsSettings.metrics_prefix, "dozer"))) {
            var db = neo4j.defaultDatabaseService();

            assertThat(JmxQueries.value(db, "dozer.metrics:name=dozer.dbms.vm.heap.used"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LONG)
                    .isPositive();
            assertThat(JmxQueries.names(db, "neo4j.metrics:*")).isEmpty();
        }
    }
}
