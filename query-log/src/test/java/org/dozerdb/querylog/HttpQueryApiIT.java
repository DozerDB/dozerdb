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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;

/**
 * A query over HTTP is logged like any other, with the source Community's connection details give that route. Neo4j
 * 2026 serves its Query API ({@code POST /db/{database}/query/v2}) over an in-process Bolt channel, so a Query API
 * query carries the Bolt shape of the source, naming the API as the client ({@code neo4j-query-api/v2}) and the
 * channel's local addresses; the transactional endpoint ({@code POST /db/{database}/tx/commit}) carries the HTTP shape
 * (scheme, client host, request path). Both are asserted here, so that the inventory's HTTP example and the release
 * gate's probe describe what the server writes.
 */
class HttpQueryApiIT {

    /** The source segment of a plain end entry, between the figures and the database's tab. */
    private static final Pattern SOURCE_SEGMENT =
            Pattern.compile(" page faults - (?<source>[^\n]*?)\t(?<database>[^\t ]*) - ");

    private static Neo4j neo4j;
    private static HttpClient http;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServerWithHttp() {
        neo4j = QueryLogServers.startWithWebServer();
        http = HttpClient.newHttpClient();
        queryLog = QueryLogFile.of(neo4j);
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aQueryApiRequestWritesAStartAndAnEndEntryWhoseSourceIsTheApisBoltChannel() throws Exception {
        String query = "RETURN 'over the query api' AS text";
        post("db/neo4j/query/v2", "{\"statement\": \"" + query + "\"}");

        String end = awaitEndEntry(query);
        String source = sourceOf(end);
        assertThat(source)
                .as("the Query API's in-process Bolt channel: %s", end)
                .startsWith("bolt-session\tbolt\tneo4j-query-api/v2\t\tclientlocal:")
                .contains("\tserverlocal:")
                .endsWith(">");
        assertThat(databaseOf(end)).isEqualTo("neo4j");
        assertThat(queryLog.startEntriesContaining(query))
                .as("one start entry with the same source")
                .hasSize(1)
                .allSatisfy(start -> assertThat(sourceOf(start)).isEqualTo(source));
        assertThat(queryLog.endEntriesContaining(query)).hasSize(1);
    }

    @Test
    void aTransactionalEndpointRequestWritesAnEndEntryWhoseSourceIsTheHttpRequest() throws Exception {
        String query = "RETURN 'over the transactional endpoint' AS text";
        post("db/neo4j/tx/commit", "{\"statements\": [{\"statement\": \"" + query + "\"}]}");

        String end = awaitEndEntry(query);
        assertThat(sourceOf(end))
                .as("Community's HTTP connection details: %s", end)
                .isEqualTo("server-session\thttp\t127.0.0.1\t/db/neo4j/tx/commit");
        assertThat(databaseOf(end)).isEqualTo("neo4j");
        assertThat(queryLog.endEntriesContaining(query)).hasSize(1);
    }

    private static void post(String path, String json) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(neo4j.httpURI().toString() + path))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(response.body()).doesNotContain("\"errors\":[{");
    }

    private static String awaitEndEntry(String query) {
        return queryLog.awaitEntryWhere(
                "the end entry of " + query,
                entry -> entry.contains(query) && !entry.contains(QueryLogFile.START_PREFIX));
    }

    private static String sourceOf(String entry) {
        return segment(entry).group("source");
    }

    private static String databaseOf(String entry) {
        return segment(entry).group("database");
    }

    private static Matcher segment(String entry) {
        Matcher matcher = SOURCE_SEGMENT.matcher(entry);
        assertThat(matcher.find()).as("a source segment in %s", entry).isTrue();
        return matcher;
    }
}
