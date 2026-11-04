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

import java.util.ServiceLoader;
import org.dozerdb.metrics.adapter.MetricsExtensionFactory;
import org.dozerdb.metrics.adapter.MetricsReportProvider;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.SettingsDeclaration;
import org.neo4j.kernel.diagnostics.DiagnosticsOfflineReportProvider;
import org.neo4j.kernel.extension.ExtensionFactory;

/**
 * Neo4j discovers extension factories, settings declarations and diagnostics report providers through Java service
 * lookup. The metrics module activates itself that way, with no change to core, so all three services
 * must be visible from the built classes.
 */
class ServiceDiscoveryTest {

    @Test
    void extensionFactoryIsFoundThroughServiceLookup() {
        assertThat(ServiceLoader.load(ExtensionFactory.class).stream().map(ServiceLoader.Provider::type))
                .contains(MetricsExtensionFactory.class);
    }

    @Test
    void settingsDeclarationIsFoundThroughServiceLookup() {
        assertThat(ServiceLoader.load(SettingsDeclaration.class).stream().map(ServiceLoader.Provider::type))
                .contains(MetricsSettings.class);
    }

    /** {@code neo4j-admin server report} collects its classifiers from the providers it finds this way. */
    @Test
    void diagnosticsReportProviderIsFoundThroughServiceLookup() {
        assertThat(ServiceLoader.load(DiagnosticsOfflineReportProvider.class).stream()
                        .map(ServiceLoader.Provider::type))
                .contains(MetricsReportProvider.class);
    }
}
