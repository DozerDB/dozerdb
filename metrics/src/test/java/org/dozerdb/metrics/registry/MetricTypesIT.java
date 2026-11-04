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
import org.dozerdb.metrics.adapter.MetricsInternalSettings;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.harness.Neo4j;

/**
 * A metric reaches JMX with the attribute shape of its registry type. The documented groups cover long gauges,
 * counters and histograms; a ratio such as the page cache hit ratio needs a gauge whose {@code Value} is a double,
 * which no transaction row has, so one is injected through the test-only {@code additional_collectors} seam.
 */
class MetricTypesIT {

    @Test
    void aDoubleGaugeReportsItsValueAsADouble() {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(
                        MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                .withConfig(
                        MetricsInternalSettings.additional_collectors, List.of(RatioGaugeCollector.class.getName())))) {
            Object value = JmxQueries.value(
                    neo4j.defaultDatabaseService(),
                    "neo4j.metrics:name=neo4j.dbms." + RatioGaugeCollector.NAME + ".value");

            assertThat(value).isEqualTo(RatioGaugeCollector.RATIO);
        }
    }
}
