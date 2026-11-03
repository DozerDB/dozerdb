/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.dozerdb.metrics.settings.MetricsSettings.CompressionOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.harness.Neo4j;

/**
 * Size-based rotation of the CSV files as an administrator sees it in {@code server.directories.metrics}: a live file
 * that has reached {@code server.metrics.csv.rotation.size} becomes the archive {@code <name>.csv.1}, older archives
 * shift to {@code .2} and beyond, the one beyond {@code server.metrics.csv.rotation.keep_number} is deleted, and the
 * next row starts a fresh live file with the header. Compressed archives carry the compression extension after the
 * index ({@code .1.zip}, {@code .1.gz}) and hold the live file's content, a ZIP archive as one entry named like the live
 * file. A size of zero never rotates. The heap gauge's file is observed; every rotation size here is a few rows.
 */
class CsvRotationIT {

    private static final String LIVE = "neo4j.dbms.vm.heap.used.csv";
    private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(1);
    private static final long ROTATION_SIZE = 200;

    /**
     * Only the heap gauge is registered, so a report writes one file: under the default filter a report writes about
     * a hundred and fifty, which on a loaded machine running the test classes in parallel left the heap gauge's
     * second rotation beyond the wait.
     */
    private static final List<GlobbingPattern> HEAP_USED_ONLY = GlobbingPattern.create("*vm.heap.used");

    /**
     * Uncompressed archives keep their order: every row of {@code .2} is no later than the first of {@code .1}, every
     * row of {@code .1} no later than the first of the live file, and {@code .2} began strictly before the live file.
     * The interval is a tenth of a second, so each archive spans about a second and the order shows in {@code t}.
     */
    @Test
    void plainArchivesShiftUpByOneIndexAndTheOldestBeyondKeepNumberIsDeleted(@TempDir Path dir) {
        rotatingServer(dir, CompressionOption.NONE, Duration.ofMillis(100), neo4j -> {
            awaitArchives(neo4j, dir, LIVE + ".1", LIVE + ".2");

            List<List<String>> snapshot = readTogether(dir, LIVE + ".2", LIVE + ".1", LIVE);
            List<String> older = snapshot.get(0);
            List<String> newer = snapshot.get(1);
            List<String> live = snapshot.get(2);
            assertThat(archives(dir)).as("never more archives than keep_number").isSubsetOf(LIVE + ".1", LIVE + ".2");
            for (List<String> file : snapshot) {
                assertThat(file).first().as("every file starts with the header").isEqualTo("t,value");
                assertThat(file.subList(1, file.size())).noneMatch(line -> line.startsWith("t,"));
            }
            assertThat(older.size())
                    .as("an archive holds the rows up to the rotation size")
                    .isGreaterThan(2);
            assertThat(lastSecond(older)).isLessThanOrEqualTo(firstSecond(newer));
            assertThat(lastSecond(newer)).isLessThanOrEqualTo(firstSecond(live));
            assertThat(firstSecond(older)).isLessThan(firstSecond(live));
        });
    }

    @Test
    void zipArchivesHoldTheLiveFileAsOneEntryAndKeepNumberIsEnforced(@TempDir Path dir) {
        rotatingServer(dir, CompressionOption.ZIP, MINIMUM_INTERVAL, neo4j -> {
            awaitArchives(neo4j, dir, LIVE + ".1.zip", LIVE + ".2.zip");

            for (int observation = 0; observation < 20; observation++) {
                assertThat(archives(dir))
                        .as("never more archives than keep_number")
                        .isSubsetOf(LIVE + ".1.zip", LIVE + ".2.zip");
                assertThat(liveLines(dir))
                        .as("the live file starts with the header")
                        .startsWith("t,value");
            }
            Await.until("a complete ZIP archive is readable", () -> {
                ZipArchive archive = zipArchive(dir.resolve(LIVE + ".1.zip"));
                return archive != null && archive.lines().size() > 1;
            });
            ZipArchive archive = zipArchive(dir.resolve(LIVE + ".1.zip"));
            assertThat(archive.entries())
                    .as("one entry, named like the live file")
                    .containsExactly(LIVE);
            assertThat(archive.lines()).first().isEqualTo("t,value");
        });
    }

    @Test
    void gzArchivesAreReadableWithGzipInputStreamAndShiftLikePlainOnes(@TempDir Path dir) {
        rotatingServer(dir, CompressionOption.GZ, MINIMUM_INTERVAL, neo4j -> {
            awaitArchives(neo4j, dir, LIVE + ".1.gz", LIVE + ".2.gz");

            assertThat(archives(dir)).isSubsetOf(LIVE + ".1.gz", LIVE + ".2.gz");
            Await.until("a complete GZ archive is readable", () -> {
                List<String> archived = gzLines(dir.resolve(LIVE + ".1.gz"));
                return archived.size() > 1 && archived.get(0).equals("t,value");
            });
        });
    }

    @Test
    void aZeroRotationSizeNeverRotates(@TempDir Path dir) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, MINIMUM_INTERVAL)
                .withConfig(MetricsSettings.metrics_filter, HEAP_USED_ONLY)
                .withConfig(MetricsSettings.csv_rotation_threshold, 0L)
                .withConfig(MetricsSettings.csv_max_archives, 2))) {
            Await.until("sixty rows are written", () -> liveLines(dir).size() > 60);

            assertThat(archives(dir)).isEmpty();
        }
    }

    private static void rotatingServer(
            Path dir, CompressionOption compression, Duration interval, Consumer<Neo4j> test) {
        try (Neo4j neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.csv_path, dir)
                .withConfig(MetricsSettings.csv_interval, interval)
                .withConfig(MetricsSettings.metrics_filter, HEAP_USED_ONLY)
                .withConfig(MetricsSettings.csv_rotation_threshold, ROTATION_SIZE)
                .withConfig(MetricsSettings.csv_max_archives, 2)
                .withConfig(MetricsSettings.csv_archives_compression, compression))) {
            test.accept(neo4j);
        }
    }

    /**
     * Waits until the archives exist; on a timeout the failure names the files present and the CSV exporter's error
     * lines, so that a slow machine and a disabled exporter can be told apart.
     */
    private static void awaitArchives(Neo4j neo4j, Path dir, String... expected) {
        try {
            Await.until("the archives " + List.of(expected) + " exist", () -> archives(dir)
                    .containsAll(Set.of(expected)));
        } catch (AssertionError timeout) {
            List<String> errors;
            try {
                errors = CsvExporterIT.errorLinesAbout(neo4j, CsvExporter.NAME);
            } catch (IOException e) {
                errors = List.of("debug.log unreadable: " + e);
            }
            throw new AssertionError(
                    timeout.getMessage() + "; files: " + CsvExporterIT.files(dir) + "; csv errors: " + errors, timeout);
        }
    }

    private static Set<String> archives(Path dir) {
        return CsvExporterIT.files(dir).stream()
                .filter(name -> name.startsWith(LIVE + "."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The lines of the named files read one right after the other, again if a rotation moved one meanwhile or a fresh
     * live file has no row yet.
     */
    private static List<List<String>> readTogether(Path dir, String... names) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            try {
                List<List<String>> snapshot = new ArrayList<>();
                for (String name : names) {
                    snapshot.add(Files.readAllLines(dir.resolve(name)));
                }
                if (snapshot.stream().allMatch(lines -> lines.size() >= 2)) {
                    return snapshot;
                }
            } catch (NoSuchFileException rotatedMeanwhile) {
                Thread.onSpinWait();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new AssertionError("The files " + List.of(names) + " never all had a row at once");
    }

    private static long firstSecond(List<String> lines) {
        return Long.parseLong(lines.get(1).split(",")[0]);
    }

    private static long lastSecond(List<String> lines) {
        return Long.parseLong(lines.get(lines.size() - 1).split(",")[0]);
    }

    /** The live file's lines, retried while a rotation has the file momentarily absent or its header unwritten. */
    private static List<String> liveLines(Path dir) {
        Path live = dir.resolve(LIVE);
        while (true) {
            Await.until("the live file is present", () -> Files.exists(live));
            try {
                List<String> lines = Files.readAllLines(live);
                if (!lines.isEmpty()) {
                    return lines;
                }
            } catch (NoSuchFileException rotatedMeanwhile) {
                // the next iteration waits for the fresh live file
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private record ZipArchive(List<String> entries, List<String> lines) {}

    /** The entry names and the first entry's lines of a ZIP archive, or {@code null} while it is incomplete. */
    private static ZipArchive zipArchive(Path archive) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            List<String> entries = new ArrayList<>();
            List<String> lines = List.of();
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                if (entries.size() == 1) {
                    lines = lines(zip);
                }
            }
            return entries.isEmpty() ? null : new ZipArchive(entries, lines);
        } catch (IOException incomplete) {
            return null;
        }
    }

    private static List<String> gzLines(Path archive) {
        try (InputStream gz = new GZIPInputStream(Files.newInputStream(archive))) {
            return lines(gz);
        } catch (IOException incomplete) {
            return List.of();
        }
    }

    /** Reads the stream's lines without closing it, so a ZIP stream can move on to its next entry. */
    private static List<String> lines(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            lines.add(line);
        }
        return lines;
    }
}
