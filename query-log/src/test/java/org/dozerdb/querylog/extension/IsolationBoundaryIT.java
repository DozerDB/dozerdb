/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.dozerdb.querylog.QueryLogFile;
import org.dozerdb.querylog.QueryLogServers;
import org.dozerdb.querylog.adapter.Neo4jDependencies;
import org.dozerdb.querylog.adapter.QueryLogExtensionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.harness.Neo4j;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;

/**
 * The isolation boundary in a running server: a second query log whose renderer throws on every entry is added through the
 * harness beside the real one. Each query still succeeds, the broken log writes a fallback entry at {@code ERROR}
 * naming the query id and the exception into {@code query.log}, and {@code debug.log} gets one error for the failure
 * kind however many entries fail. The real query log, unaffected, writes its normal entries into the same file.
 */
class IsolationBoundaryIT {

    private static final String RENDERER_FAILURE = "renderer broke on purpose";

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServerWithAThrowingQueryLog() {
        neo4j = QueryLogServers.startWithBolt(
                builder -> QueryLogServers.withExtensionFactories(builder, new ThrowingRendererQueryLogFactory()));
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
    void aThrowingRendererLeavesTheQuerySucceedingAndMarksTheGapInBothLogs() throws IOException {
        String first = "RETURN 'first survives the broken renderer' AS text";
        String second = "RETURN 'second survives the broken renderer' AS text";
        try (Session session = driver.session()) {
            assertThat(session.run(first).single().get("text").asString()).contains("first");
            assertThat(session.run(second).single().get("text").asString()).contains("second");
        }
        String sentinel = QueryLogFile.sentinelQuery();
        try (Session session = driver.session()) {
            session.run(sentinel).consume();
        }
        queryLog.awaitEntryContaining(sentinel);

        String normalEntry = queryLog.endEntriesContaining(first).get(0);
        String queryId = normalEntry.replaceAll("^.* INFO  id:(\\d+) - .*$", "$1");
        assertThat(queryId)
                .as("the real query log wrote its entry: %s", normalEntry)
                .matches("\\d+");
        assertThat(queryLog.entriesContaining(IsolationBoundary.FALLBACK_PREFIX + queryId + " - "))
                .as("one fallback entry for the start entry and one for the end entry")
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry)
                        .contains(" ERROR " + IsolationBoundary.FALLBACK_PREFIX + queryId
                                + " - java.lang.IllegalStateException: " + RENDERER_FAILURE));
        assertThat(queryLog.entriesContaining(IsolationBoundary.FALLBACK_PREFIX))
                .as("one fallback entry per failed entry, start and end, the sentinel's included")
                .hasSizeGreaterThanOrEqualTo(6);
        assertThat(debugLogErrorsAbout(RENDERER_FAILURE))
                .as("one debug.log error per failure kind: the start callback and the end callback")
                .hasSize(2)
                .anyMatch(line -> line.contains("'query start' failed"))
                .anyMatch(line -> line.contains("'query end' failed"));
    }

    private static List<String> debugLogErrorsAbout(String text) throws IOException {
        Path debugLog = neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        return Files.readAllLines(debugLog).stream()
                .filter(line -> line.contains("ERROR") && line.contains(text))
                .toList();
    }

    /** A second query log extension, built like the real one but with a renderer that always throws. */
    private static final class ThrowingRendererQueryLogFactory extends ExtensionFactory<Neo4jDependencies> {

        ThrowingRendererQueryLogFactory() {
            super(ExtensionType.GLOBAL, "throwing-renderer-query-log");
        }

        @Override
        public Lifecycle newInstance(ExtensionContext context, Neo4jDependencies dependencies) {
            return QueryLogExtensionFactory.lifecycleOf(dependencies, (entry, format) -> {
                throw new IllegalStateException(RENDERER_FAILURE);
            });
        }
    }
}
