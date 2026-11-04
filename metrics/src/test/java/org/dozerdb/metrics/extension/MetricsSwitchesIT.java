/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import static org.assertj.core.api.Assertions.assertThat;

import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;

/**
 * {@code server.metrics.enabled=false} registers nothing and starts no exporter; {@code server.metrics.jmx.enabled=false}
 * registers no MBeans. Either way the {@code <prefix>.metrics} JMX domain is empty and the server serves.
 */
class MetricsSwitchesIT {

    @Test
    void disabledMetricsLeaveTheJmxDomainEmpty() {
        try (Neo4j neo4j =
                MetricsServers.start(builder -> builder.withConfig(MetricsSettings.metrics_enabled, false))) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:*"))
                    .isEmpty();
        }
    }

    @Test
    void disabledJmxExporterRegistersNoMBeans() {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.jmx_enabled, false))) {
            assertThat(JmxQueries.names(neo4j.defaultDatabaseService(), "neo4j.metrics:*"))
                    .isEmpty();
        }
    }
}
