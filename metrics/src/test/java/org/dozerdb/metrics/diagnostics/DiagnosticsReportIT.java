/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.harness.Neo4j;

/**
 * {@code neo4j-admin server report} as an administrator runs it against a server home: the {@code metrics}
 * classifier carries the metrics directory. A server runs with default settings and the minimum CSV interval
 * until the directory holds a live file and archives, is stopped, and the report is run against its home; the
 * archive must hold every file of that directory under {@code metrics/}, byte for byte. A home whose server wrote
 * no CSV files reports successfully with nothing under {@code metrics/}; a configured {@code server.directories.metrics}
 * in {@code conf/neo4j.conf} is resolved as the exporter resolved it; {@code --list} names the classifier. Nothing
 * here reads the registry or calls the provider.
 */
class DiagnosticsReportIT {

    private static final String ARCHIVE_FOLDER = "metrics/";
    private static final String HEAP_USED_FILE = "neo4j.dbms.vm.heap.used.csv";
    private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(1);

    @Test
    void theMetricsClassifierArchivesEveryFileOfTheMetricsDirectory(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("home");
        Path metrics = home.resolve("metrics");
        try (Neo4j ignored = MetricsServers.startInHome(
                home, builder -> builder.withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                        .withConfig(MetricsSettings.csv_rotation_threshold, 200L)
                        .withConfig(MetricsSettings.csv_max_archives, 2))) {
            // Archives are uncompressed under the Operations Manual 5 default
            // (server.metrics.csv.rotation.compression=NONE).
            Await.until(
                    "the live file and an archive of the heap gauge exist",
                    () -> files(metrics).keySet().containsAll(Set.of(HEAP_USED_FILE, HEAP_USED_FILE + ".1")));
        }
        Map<String, byte[]> written = files(metrics);

        AdminReport.Result report = AdminReport.run(home, dir.resolve("reports"), "metrics");

        assertThat(report.exitCode()).as(report.output()).isZero();
        Map<String, byte[]> archived = report.entries();
        assertThat(archived.keySet().stream().filter(name -> name.startsWith(ARCHIVE_FOLDER)))
                .as("one archive entry per file of the metrics directory")
                .containsExactlyInAnyOrderElementsOf(written.keySet().stream()
                        .map(name -> ARCHIVE_FOLDER + name)
                        .toList());
        written.forEach((name, bytes) -> assertThat(archived.get(ARCHIVE_FOLDER + name))
                .as("the archived %s is the file as written", name)
                .isEqualTo(bytes));
    }

    @Test
    void aHomeWithoutCsvFilesReportsSuccessfullyWithAnEmptyMetricsClassifier(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("home");
        Path metrics = home.resolve("metrics");
        try (Neo4j ignored =
                MetricsServers.startInHome(home, builder -> builder.withConfig(MetricsSettings.csv_enabled, false))) {
            assertThat(metrics)
                    .as("a disabled CSV exporter creates no metrics directory")
                    .doesNotExist();
        }

        AdminReport.Result withoutDirectory = AdminReport.run(home, dir.resolve("reports-without"), "metrics");
        Files.createDirectory(metrics);
        AdminReport.Result withEmptyDirectory = AdminReport.run(home, dir.resolve("reports-empty"), "metrics");

        for (AdminReport.Result report : List.of(withoutDirectory, withEmptyDirectory)) {
            assertThat(report.exitCode()).as(report.output()).isZero();
            Map<String, byte[]> archived = report.entries();
            assertThat(archived.keySet())
                    .as("an archive with a manifest and nothing under metrics/")
                    .contains("manifest.json")
                    .noneMatch(name -> name.startsWith(ARCHIVE_FOLDER));
            assertThat(classifiersInManifest(archived.get("manifest.json")))
                    .as("the manifest records no source for the metrics classifier")
                    .doesNotContain("metrics");
        }
    }

    @Test
    void aConfiguredMetricsDirectoryIsResolvedAgainstTheHomeAsTheCsvExporterResolvesIt(@TempDir Path dir)
            throws IOException {
        Path home = dir.resolve("home");
        Path configured = Path.of("custom-metrics");
        Path metrics = home.resolve(configured);
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(
                home.resolve("conf").resolve("neo4j.conf"), MetricsSettings.csv_path.name() + "=" + configured + "\n");
        try (Neo4j ignored =
                MetricsServers.startInHome(home, builder -> builder.withConfig(MetricsSettings.csv_path, configured)
                        .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL))) {
            Await.until("the heap gauge file exists in the configured directory", () -> files(metrics)
                    .containsKey(HEAP_USED_FILE));
        }
        assertThat(home.resolve("metrics"))
                .as("nothing was written to the default directory")
                .doesNotExist();

        AdminReport.Result report = AdminReport.run(home, dir.resolve("reports"), "metrics");

        assertThat(report.exitCode()).as(report.output()).isZero();
        assertThat(report.entries().keySet().stream().filter(name -> name.startsWith(ARCHIVE_FOLDER)))
                .containsExactlyInAnyOrderElementsOf(files(metrics).keySet().stream()
                        .map(name -> ARCHIVE_FOLDER + name)
                        .toList());
    }

    @Test
    void theListOfClassifiersNamesMetrics(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve("data").resolve("databases").resolve("neo4j"));

        AdminReport.Result report = AdminReport.run(home, dir.resolve("reports"), "--list");

        assertThat(report.exitCode()).as(report.output()).isZero();
        assertThat(report.out().lines())
                .as("the line neo4j-admin prints for the classifier")
                .anyMatch(Pattern.compile("^\\s+metrics\\s+include metrics$").asMatchPredicate());
        assertThat(dir.resolve("reports")).as("--list writes no archive").doesNotExist();
    }

    private static List<String> classifiersInManifest(byte[] manifest) throws IOException {
        JsonNode classifiers = new ObjectMapper().readTree(manifest).get("classifiers");
        return classifiers.findValuesAsText("name");
    }

    /**
     * The regular files of a directory with their bytes, keyed by file name; empty when the directory is absent. A
     * file the exporter rotated away between the listing and the read (the live server rotates every few rows here)
     * is left out; the poll reads again.
     */
    static Map<String, byte[]> files(Path directory) {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                try {
                    files.put(path.getFileName().toString(), Files.readAllBytes(path));
                } catch (NoSuchFileException rotatedAway) {
                    // listed a moment ago, renamed to an archive since
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
