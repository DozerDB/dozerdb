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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.dozerdb.metrics.adapter.MetricsExtensionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The metrics artifact as it ships: one JAR holding the module's classes,
 * Dropwizard Metrics relocated under {@code org.dozerdb.metrics.shaded}, the service files through which Neo4j
 * discovers the module, and the DozerDB third-party notice. The same JAR is shaded into the plugin and placed in
 * the distribution's {@code lib/}, so what holds here holds in both delivery forms.
 *
 * <p>Failsafe runs the integration tests against the packaged JAR, which is what this test inspects. Run from a
 * classes directory (an IDE), it is skipped with a reason rather than asserting against the wrong artifact.
 */
class ShadedArtifactIT {

    private static final String DOZERDB_PACKAGE = "org/dozerdb/metrics/";
    private static final String RELOCATED_DROPWIZARD = DOZERDB_PACKAGE + "shaded/com/codahale/metrics/";
    private static final String NOTICE = "META-INF/DOZERDB-NOTICE.txt";

    private static Path jar;
    private static List<String> entries;

    @BeforeAll
    static void locateThePackagedJar() throws URISyntaxException, IOException {
        jar = Path.of(MetricsExtensionFactory.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        assumeTrue(
                jar.getFileName().toString().endsWith(".jar"),
                "inspects the packaged metrics JAR (failsafe); the classes came from " + jar);
        try (JarFile file = new JarFile(jar.toFile())) {
            entries = file.stream().map(entry -> entry.getName()).toList();
        }
    }

    @Test
    void dropwizardIsRelocatedUnderTheDozerDbPackageAndNothingElseIsBundled() {
        assertThat(entries)
                .as("no library class keeps its original name, and the host provides slf4j")
                .noneMatch(name -> name.startsWith("com/codahale/") || name.startsWith("org/slf4j/"));
        assertThat(entries)
                .contains(
                        RELOCATED_DROPWIZARD + "MetricRegistry.class",
                        RELOCATED_DROPWIZARD + "jmx/JmxReporter.class",
                        RELOCATED_DROPWIZARD + "graphite/GraphiteReporter.class",
                        RELOCATED_DROPWIZARD + "graphite/Graphite.class");
        assertThat(entries)
                .as("metrics-graphite's RabbitMQ client is excluded, as in the Neo4j parent")
                .noneMatch(name -> name.startsWith("com/rabbitmq/"));
        assertThat(entries.stream().filter(name -> name.endsWith(".class")))
                .as("every class in the artifact is DozerDB's own or relocated under its package")
                .allMatch(name -> name.startsWith(DOZERDB_PACKAGE));
    }

    @Test
    void neo4jDiscoversTheExtensionTheSettingsAndTheReportProviderThroughTheJarsServiceFiles() throws IOException {
        assertThat(read("META-INF/services/org.neo4j.kernel.extension.ExtensionFactory"))
                .contains(MetricsExtensionFactory.class.getName());
        assertThat(read("META-INF/services/org.neo4j.configuration.SettingsDeclaration"))
                .contains("org.dozerdb.metrics.settings.MetricsSettings");
        assertThat(read("META-INF/services/org.neo4j.kernel.diagnostics.DiagnosticsOfflineReportProvider"))
                .as("neo4j-admin server report finds the metrics classifier's provider")
                .contains("org.dozerdb.metrics.adapter.MetricsReportProvider");
    }

    /**
     * The Neo4j parent would copy the module directory's README into {@code META-INF}; the module's README is a
     * maintainer document, so neither it nor any other Markdown file ships in the artifact.
     */
    @Test
    void theJarCarriesNoMarkdownDocument() {
        assertThat(entries).noneMatch(name -> name.endsWith(".md"));
    }

    @Test
    void theJarCarriesTheThirdPartyNoticeForTheLibrariesDozerDbAdds() throws IOException {
        String dropwizardVersion = System.getProperty("dropwizard.version");
        assertThat(dropwizardVersion)
                .as("failsafe passes the Dropwizard version the Neo4j parent manages")
                .isNotBlank();

        String notice = read(NOTICE);

        assertThat(notice)
                .contains("Dropwizard Metrics " + dropwizardVersion)
                .contains("metrics-core", "metrics-jmx", "metrics-graphite")
                .contains("Prometheus simpleclient 0.16.0")
                .contains("Apache License, Version 2.0");
    }

    private static String read(String entry) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var jarEntry = file.getJarEntry(entry);
            assertThat(jarEntry).as(entry + " is in " + jar).isNotNull();
            return new String(file.getInputStream(jarEntry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
