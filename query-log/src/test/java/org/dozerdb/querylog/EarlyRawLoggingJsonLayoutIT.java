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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.dozerdb.querylog.inventory.FieldInventory;
import org.dozerdb.querylog.inventory.ReleaseLines;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * Under the JSON layout, the early start entry of {@code db.logs.query.early_raw_logging_enabled} is a {@code start}
 * entry whose {@code transactionId} is {@code 0}, whose {@code database} is {@code <none>}, whose {@code query} and
 * {@code queryParameters} are the raw ones even under {@code obfuscate_literals}, and which has no {@code runtime} and
 * no {@code queryLang}, since the query has reached neither; every key it does carry is one the field inventory
 * documents for a start entry at {@code VERBOSE}. The end entry of the same query keeps the obfuscation rules and the
 * same {@code id}. The logging configuration is the same minimal file as {@link JsonLayoutIT}'s.
 */
class EarlyRawLoggingJsonLayoutIT {

    private static final Set<String> LAYOUT_KEYS = Set.of("time", "level", "stacktrace");
    private static final FieldInventory INVENTORY = FieldInventory.load();

    private static Path home;
    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServerWithTheJsonLayoutAndEarlyRawLogging() throws IOException {
        home = Files.createTempDirectory("dozerdb-query-log-json-early");
        Path xml = home.resolve("server-logs.xml");
        Files.writeString(xml, JsonLayoutIT.minimalServerLogsXml(), StandardCharsets.UTF_8);
        neo4j = QueryLogServers.startWithBolt(
                builder -> builder.withConfig(GraphDatabaseSettings.neo4j_home, home.toAbsolutePath())
                        .withConfig(GraphDatabaseSettings.server_logging_config_path, xml.toAbsolutePath())
                        .withConfig(GraphDatabaseSettings.log_queries_early_raw_logging_enabled, true)
                        .withConfig(GraphDatabaseSettings.log_queries_obfuscate_literals, true));
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
        queryLog = QueryLogFile.of(neo4j);
    }

    @AfterAll
    static void stopServer() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void theEarlyStartEntryIsRawWithTransactionIdZeroAndNoDatabaseAndTheEndEntryIsObfuscatedWithTheSameId() {
        String literal = "json-early-" + UUID.randomUUID();
        String query = "RETURN '" + literal + "' AS text, $p AS parameter";
        run(query, Map.of("p", "raw value"));

        JsonNode start =
                queryLog.awaitJsonEntryWhere("the early start entry", entry -> isEntryOf(entry, "start", query));
        assertStartKeysAreInventoryRows(start);
        assertThat(start.get("transactionId").asLong()).isZero();
        assertThat(start.get("database").asText()).isEqualTo("<none>");
        assertThat(start.get("queryParameters").asText()).isEqualTo("{p: 'raw value'}");
        assertThat(start.get("executionPlanCacheKeyHash").asText()).isEqualTo("00000000");
        assertThat(start.has("runtime")).as("no runtime yet: %s", start).isFalse();
        assertThat(start.has("queryLang"))
                .as("the query language is settled by the pre-parse step after this event: %s", start)
                .isFalse();

        String obfuscated = "RETURN ****** AS text, $p AS parameter";
        JsonNode end = queryLog.awaitJsonEntryWhere(
                "the obfuscated end entry",
                entry -> isEntryOf(entry, "success", obfuscated)
                        && entry.path("id").asText().equals(start.get("id").asText()));
        assertThat(end.get("database").asText()).isEqualTo("neo4j");
        assertThat(end.get("transactionId").asLong()).isPositive();
        assertThat(end.get("queryParameters").asText()).isEqualTo("{p: 'raw value'}");
        if (INVENTORY.tier("queryLang", ReleaseLines.current()).equals("not-applicable")) {
            assertThat(end.has("queryLang"))
                    .as("the line's Neo4j has no query language to write: %s", end)
                    .isFalse();
        } else {
            assertThat(end.get("queryLang").asText()).startsWith("CYPHER ");
        }
        assertThat(queryLog.jsonEntriesWhere(entry -> entry.path("event")
                                .asText()
                                .equals("start")
                        && entry.path("id").asText().equals(start.get("id").asText())))
                .as("exactly one start entry for the query")
                .hasSize(1);
    }

    @Test
    void aQueryThatFailsToParseHasItsRawTextInTheEarlyStartEntryAndNoQueryKeyInTheWithheldFailedEntry() {
        String query = "RETURN 'json-withheld-" + UUID.randomUUID() + "' (";
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
        }

        JsonNode start =
                queryLog.awaitJsonEntryWhere("the early start entry", entry -> isEntryOf(entry, "start", query));
        assertStartKeysAreInventoryRows(start);
        String id = start.get("id").asText();
        JsonNode failed = queryLog.awaitJsonEntryWhere(
                "the failed-query entry id=" + id,
                entry -> entry.path("event").asText().equals("fail")
                        && entry.path("id").asText().equals(id));
        assertThat(failed.has("query"))
                .as("the text of a query that could not be parsed is withheld under obfuscate_literals: %s", failed)
                .isFalse();
        assertThat(failed.get("database").asText()).isEqualTo("<none>");
        assertThat(failed.get("transactionId").asLong())
                .as("Neo4j's own value on a query that ended without being bound")
                .isEqualTo(-1);
    }

    /** Every key of the early start entry is a row the inventory documents for a start entry at VERBOSE, pattern included. */
    private static void assertStartKeysAreInventoryRows(JsonNode start) {
        List<String> keys = new ArrayList<>();
        start.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("entry %s", start).startsWith("time", "level");
        assertThat(start.get("level").asText()).isEqualTo("INFO");
        for (String key : keys) {
            if (LAYOUT_KEYS.contains(key)) {
                continue;
            }
            JsonNode row = INVENTORY.fields().stream()
                    .filter(candidate -> !candidate.get("jsonKey").isNull()
                            && candidate.get("jsonKey").asText().equals(key))
                    .filter(candidate -> !candidate.get("type").asText().equals("transaction"))
                    .filter(candidate ->
                            contains(candidate.get("entries"), "start") && contains(candidate.get("levels"), "VERBOSE"))
                    .findFirst()
                    .orElseThrow(
                            () -> new AssertionError("the inventory has no JSON row '" + key + "' for a start entry"));
            JsonNode value = start.get(key);
            if (row.get("pattern").isNull()) {
                assertThat(value.isObject())
                        .as("%s is object-valued: %s", key, start)
                        .isTrue();
            } else {
                String text = value.isObject() ? value.toString() : value.asText();
                assertThat(text)
                        .as("%s matches the inventory pattern of %s", value, key)
                        .matches(row.get("pattern").asText());
            }
        }
    }

    private static boolean contains(JsonNode array, String word) {
        for (JsonNode element : array) {
            if (element.asText().equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEntryOf(JsonNode entry, String event, String query) {
        return entry.path("event").asText().equals(event)
                && entry.path("query").asText().equals(query);
    }

    private static void run(String query, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            session.run(query, parameters).consume();
        }
    }
}
