/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.harness.Neo4j;

/**
 * Reads the {@code query.log} a server writes, the primary seam of every in-server test. The end monitor fires
 * after the client may already hold its result, so every expectation polls; an absence is asserted only after a
 * sentinel entry, a query the test runs on purpose, has appeared. An entry is one line that starts with the layout's
 * timestamp plus any continuation lines (a stack trace, a plan table) up to the next timestamp. Under the JSON layout
 * an entry is one JSON object per line, read through {@link #jsonEntries()}.
 */
public final class QueryLogFile {

    /** The {@code %m} prefix of a start entry. */
    public static final String START_PREFIX = "Query started: ";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern ENTRY_START =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4} ");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;

    private QueryLogFile(Path path) {
        this.path = path;
    }

    /** The {@code query.log} of a running server, wherever its logs directory is. */
    public static QueryLogFile of(Neo4j server) {
        return new QueryLogFile(
                server.config().get(GraphDatabaseSettings.logs_directory).resolve("query.log"));
    }

    /** The {@code query.log} of a Neo4j home, for a server that has been closed. */
    public static QueryLogFile inHome(Path home) {
        return new QueryLogFile(home.resolve("logs").resolve("query.log"));
    }

    public Path path() {
        return path;
    }

    /** A query text no other test writes, to run as the sentinel query and wait for. */
    public static String sentinelQuery() {
        return "RETURN 'sentinel-" + UUID.randomUUID() + "' AS sentinel";
    }

    /**
     * A sentinel query that fails to compile (an undefined variable), for a server whose detail level is {@code INFO}
     * with a threshold: a successful sentinel would be hidden there, a failure is written whatever the threshold.
     */
    public static String failingSentinelQuery() {
        return "RETURN sentinel_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Every entry in the file right now; an absent file has none. */
    public List<String> entries() {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        StringBuilder current = null;
        for (String line : readLines()) {
            if (ENTRY_START.matcher(line).find()) {
                if (current != null) {
                    entries.add(current.toString());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            entries.add(current.toString());
        }
        return entries;
    }

    /** The entries whose text contains {@code text}, right now. */
    public List<String> entriesContaining(String text) {
        return entries().stream().filter(entry -> entry.contains(text)).toList();
    }

    /** The start entries ({@code Query started: }, written at {@code VERBOSE}) whose text contains {@code text}. */
    public List<String> startEntriesContaining(String text) {
        return entriesContaining(text).stream()
                .filter(QueryLogFile::isStartEntry)
                .toList();
    }

    /** The end entries (success entries and failed-query entries) whose text contains {@code text}. */
    public List<String> endEntriesContaining(String text) {
        return entriesContaining(text).stream()
                .filter(entry -> !isStartEntry(entry))
                .toList();
    }

    private static boolean isStartEntry(String entry) {
        return entry.contains(" INFO  " + START_PREFIX);
    }

    /** Waits until at least one entry contains {@code text} and returns the first; fails after the timeout. */
    public String awaitEntryContaining(String text) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            List<String> matching = entriesContaining(text);
            if (!matching.isEmpty()) {
                return matching.get(0);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting for an entry containing '" + text
                        + "' in " + path + "; entries so far:\n" + String.join("\n", entries()));
            }
            pause();
        }
    }

    /** Waits until at least one entry satisfies {@code condition} and returns the first; fails after the timeout. */
    public String awaitEntryWhere(String description, Predicate<String> condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            List<String> matching = entries().stream().filter(condition).toList();
            if (!matching.isEmpty()) {
                return matching.get(0);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting for " + description + " in " + path
                        + "; entries so far:\n" + String.join("\n", entries()));
            }
            pause();
        }
    }

    /** Every entry of a file written by the JSON layout: one JSON object per line; a line that is not one fails. */
    public List<JsonNode> jsonEntries() {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<JsonNode> entries = new ArrayList<>();
        for (String line : readLines()) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode entry = JSON.readTree(line);
                if (!entry.isObject()) {
                    throw new AssertionError("Not one JSON object per line in " + path + ": " + line);
                }
                entries.add(entry);
            } catch (IOException e) {
                throw new AssertionError("Not valid JSON in " + path + ": " + line, e);
            }
        }
        return entries;
    }

    /** The JSON entries that satisfy {@code condition}, right now. */
    public List<JsonNode> jsonEntriesWhere(Predicate<JsonNode> condition) {
        return jsonEntries().stream().filter(condition).toList();
    }

    /** Waits until at least one JSON entry satisfies {@code condition} and returns the first; fails after the timeout. */
    public JsonNode awaitJsonEntryWhere(String description, Predicate<JsonNode> condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            List<JsonNode> matching = jsonEntriesWhere(condition);
            if (!matching.isEmpty()) {
                return matching.get(0);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting for a JSON entry where " + description
                        + " in " + path + "; entries so far:\n" + String.join("\n", readLines()));
            }
            pause();
        }
    }

    private List<String> readLines() {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for query.log", e);
        }
    }
}
