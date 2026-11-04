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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.neo4j.cli.AdminTool;
import org.neo4j.cli.ExecutionContext;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;

/**
 * Runs {@code neo4j-admin server report} in this JVM against a server home, the way the launcher script runs it
 * after forking: {@link AdminTool} is the tool's entry point, resolves the {@code report} command and the report
 * providers through service lookup, and writes the archive to {@code --to-path}. Tests observe the archive.
 */
final class AdminReport {

    private AdminReport() {}

    /** {@code neo4j-admin server report --to-path=<reports> <arguments>} with {@code <home>/conf} as the config directory. */
    static Result run(Path home, Path reports, String... arguments) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (DefaultFileSystemAbstraction fs = new DefaultFileSystemAbstraction();
                PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            ExecutionContext ctx = new ExecutionContext(home, home.resolve("conf"), outStream, errStream, fs);
            String[] args = Stream.concat(
                            Stream.of("server", "report", "--to-path=" + reports), Arrays.stream(arguments))
                    .toArray(String[]::new);
            int exitCode = AdminTool.execute(ctx, args);
            return new Result(
                    exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), reports);
        }
    }

    /** The outcome of one run: the exit code, what the tool printed, and the archive it wrote. */
    record Result(int exitCode, String out, String err, Path reports) {

        String output() {
            return "exit code " + exitCode + "\n--- stdout ---\n" + out + "\n--- stderr ---\n" + err;
        }

        /** The one archive the report wrote. */
        Path archive() {
            List<Path> archives = list(reports);
            if (archives.size() != 1) {
                throw new AssertionError("Expected exactly one report archive in " + reports + " but found " + archives
                        + "\n" + output());
            }
            return archives.get(0);
        }

        /** Every entry of the archive with its bytes, keyed by entry name. */
        Map<String, byte[]> entries() {
            Map<String, byte[]> entries = new TreeMap<>();
            try (ZipFile zip = new ZipFile(archive().toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entries;
        }

        private static List<Path> list(Path directory) {
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(directory)) {
                return files.toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
