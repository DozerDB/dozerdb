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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.harness.DozerDbBuilders;

/**
 * Invalid metrics settings fail startup through Neo4j's ordinary settings validation, exactly as in Neo4j. Only
 * runtime failures of collectors and exporters are isolated; configuration errors are not.
 *
 * <p>A value the in-process builder can carry (a typed value below a documented minimum) is tried on a server start.
 * A value only a {@code neo4j.conf} line can express (text that does not parse) goes through Neo4j's {@link Config}
 * builder with the raw text, which is the path every {@code neo4j.conf} line takes before the server starts.
 */
class SettingsValidationIT {

    @Test
    void aKeepNumberBelowOneFailsStartup() {
        assertThatThrownBy(() -> start(MetricsSettings.csv_max_archives, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.metrics.csv.rotation.keep_number")
                .hasMessageContaining("minimum allowed value is 1");
    }

    @Test
    void aCsvIntervalBelowOneMillisecondFailsStartup() {
        assertThatThrownBy(() -> start(MetricsSettings.csv_interval, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.metrics.csv.interval")
                .hasMessageContaining("minimum allowed value is 1ms");
    }

    @Test
    void aNegativeRotationSizeFailsStartup() {
        assertThatThrownBy(() -> start(MetricsSettings.csv_rotation_threshold, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.metrics.csv.rotation.size");
    }

    /** The settings reference lists exactly NONE, ZIP and GZ; any other text in neo4j.conf is rejected. */
    @Test
    void compressionAcceptsOnlyTheDocumentedOptions() {
        for (String option : new String[] {"NONE", "ZIP", "GZ"}) {
            Config config = Config.newBuilder()
                    .setRaw(Map.of("server.metrics.csv.rotation.compression", option))
                    .build();
            assertThat(config.get(MetricsSettings.csv_archives_compression).name())
                    .isEqualTo(option);
        }
        assertThatThrownBy(() -> Config.newBuilder()
                        .setRaw(Map.of("server.metrics.csv.rotation.compression", "BZIP"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.metrics.csv.rotation.compression");
    }

    /**
     * The Operations Manual 5 settings reference has no {@code server.metrics.deny_filter} (it arrived in a later
     * Neo4j), so DozerDB on the LTS line does not declare it: a {@code neo4j.conf} written for a later Neo4j that sets
     * it fails under strict settings validation, as it does on Neo4j 5.26.
     */
    @Test
    void aDenyFilterIsNotASettingOfThisLineAndFailsStrictValidation() {
        assertThatThrownBy(() -> Config.newBuilder()
                        .setRaw(Map.of("server.metrics.deny_filter", "*vm.*"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.metrics.deny_filter");
    }

    private static <T> void start(Setting<T> setting, T value) {
        DozerDbBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .withConfig(setting, value)
                .build()
                .close();
    }
}
