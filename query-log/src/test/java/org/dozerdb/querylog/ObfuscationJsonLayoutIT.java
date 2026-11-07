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
import java.util.Map;
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
 * Under the JSON layout, {@code db.logs.query.parameter_logging_enabled=false} leaves the {@code queryParameters} key
 * out of both the start and the end entry, and {@code db.logs.query.obfuscate_errors=true} writes the failed-query
 * entry with the obfuscated status description as its {@code failureReason} and throughout its {@code errorInfo} tree
 * (no message text) and with no {@code stacktrace}. The logging configuration is the same minimal file as
 * {@link JsonLayoutIT}'s.
 */
class ObfuscationJsonLayoutIT {

    private static Path home;
    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServerWithTheJsonLayout() throws IOException {
        home = Files.createTempDirectory("dozerdb-query-log-json-obfuscation");
        Path xml = home.resolve("server-logs.xml");
        Files.writeString(xml, JsonLayoutIT.minimalServerLogsXml(), StandardCharsets.UTF_8);
        neo4j = QueryLogServers.startWithBolt(
                builder -> builder.withConfig(GraphDatabaseSettings.neo4j_home, home.toAbsolutePath())
                        .withConfig(GraphDatabaseSettings.server_logging_config_path, xml.toAbsolutePath()));
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
    void withParameterLoggingOffNeitherEntryHasTheQueryParametersKey() {
        String query = "MATCH (p:Person {name: $name}) RETURN p.born + $n // json, no parameters";
        Map<String, Object> parameters = Map.of("name", "Alexander the Great", "n", 42);
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_parameter_logging_enabled, false);
        try {
            run(query, parameters);
            JsonNode end = queryLog.awaitJsonEntryWhere("the end entry", entry -> isEntryOf(entry, "success", query));
            JsonNode start = queryLog.awaitJsonEntryWhere("the start entry", entry -> isEntryOf(entry, "start", query));
            assertThat(end.has("queryParameters")).as("%s", end).isFalse();
            assertThat(start.has("queryParameters")).as("%s", start).isFalse();
            assertThat(end.toString()).doesNotContain("Alexander");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_parameter_logging_enabled, true);
        }
        String shown = "MATCH (p:Person {name: $name}) RETURN p.born + $n // json, parameters again";
        run(shown, parameters);
        JsonNode end = queryLog.awaitJsonEntryWhere("the end entry", entry -> isEntryOf(entry, "success", shown));
        assertThat(end.get("queryParameters").asText()).isEqualTo("{name: 'Alexander the Great', n: 42}");
    }

    @Test
    void underObfuscateErrorsTheFailedEntryCarriesTheObfuscatedDescriptionsAndNoStackTrace() {
        String query = "MATCH (n) RETURN obfuscatedJsonVariable";
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_errors, true);
        try {
            try (Session session = driver.session()) {
                assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
            }
            JsonNode entry = queryLog.awaitJsonEntryWhere("the failed entry", e -> isEntryOf(e, "fail", query));

            assertThat(entry.get("level").asText()).isEqualTo("ERROR");
            if (RouterRaisedError.onTheRunningLine()) {
                // Neo4j 5.26 raises "Variable not defined" without a GQL status of its own, so the router reports the
                // default status, and its standard description is what obfuscate_errors writes.
                String general = RouterRaisedError.standardDescription();
                assertThat(entry.has("stacktrace"))
                        .as("no stack trace: %s", entry)
                        .isFalse();
                assertThat(entry.get("failureReason").asText()).isEqualTo(general);
                assertThat(entry.get("errorInfo").get("GQLSTATUS").asText()).isEqualTo(RouterRaisedError.status());
                assertThat(entry.get("errorInfo").get("statusDescription").asText())
                        .isEqualTo(general);
                assertThat(entry.toString()).doesNotContain("Unexpected error has occurred");
                return;
            }
            String description = "error: syntax error or access rule violation - invalid syntax";
            assertThat(entry.has("stacktrace")).as("no stack trace: %s", entry).isFalse();
            assertThat(entry.get("failureReason").asText()).isEqualTo(description);
            JsonNode errorInfo = entry.get("errorInfo");
            assertThat(errorInfo.get("GQLSTATUS").asText()).isEqualTo("42001");
            assertThat(errorInfo.get("statusDescription").asText()).isEqualTo(description);
            assertThat(errorInfo.get("classification").asText()).isEqualTo("CLIENT_ERROR");
            assertThat(errorInfo.has("position")).isTrue();
            // The nested cause keeps the variable name: Neo4j lists it as a non-sensitive parameter of 42N62.
            JsonNode cause = errorInfo.get("cause");
            assertThat(cause.get("GQLSTATUS").asText()).isEqualTo("42N62");
            assertThat(cause.get("statusDescription").asText())
                    .isEqualTo("error: syntax error or access rule violation - variable not defined."
                            + " Variable `obfuscatedJsonVariable` not defined.");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_errors, false);
        }
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
