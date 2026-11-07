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

import java.util.concurrent.atomic.AtomicInteger;
import org.dozerdb.querylog.adapter.Neo4jDependencies;
import org.dozerdb.querylog.adapter.QueryLogExtensionFactory;
import org.dozerdb.querylog.extension.RenderedEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.GraphDatabaseSettings.LogQueryLevel;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;

/**
 * {@code db.logs.query.enabled=OFF} writes nothing, successes and failures alike, and costs nothing beyond reading the
 * configuration: a second query log whose renderer counts its calls, added beside the real one, is never asked for an
 * entry while the level is {@code OFF}. The absence is judged after a sentinel entry from a second server, the only
 * server that can write one, has appeared. Switching the level on at runtime (on the live configuration, as
 * Enterprise's {@code dbms.setConfigValue} does) makes the next query appear in both query logs without a restart.
 */
class OffDetailLevelIT {

    private static final AtomicInteger RENDERED_ENTRIES = new AtomicInteger();

    private static Neo4j offServer;
    private static Driver offDriver;
    private static QueryLogFile offQueryLog;
    private static Neo4j clockServer;
    private static Driver clockDriver;
    private static QueryLogFile clockQueryLog;

    @BeforeAll
    static void startServers() {
        offServer = QueryLogServers.startWithBolt(builder -> {
            builder.withConfig(GraphDatabaseSettings.log_queries, LogQueryLevel.OFF);
            QueryLogServers.withExtensionFactories(builder, new CountingRendererQueryLogFactory());
        });
        offDriver = GraphDatabase.driver(offServer.boltURI(), AuthTokens.none());
        offQueryLog = QueryLogFile.of(offServer);
        clockServer = QueryLogServers.startWithBolt();
        clockDriver = GraphDatabase.driver(clockServer.boltURI(), AuthTokens.none());
        clockQueryLog = QueryLogFile.of(clockServer);
    }

    @AfterAll
    static void stopServers() {
        for (Driver driver : new Driver[] {offDriver, clockDriver}) {
            if (driver != null) {
                driver.close();
            }
        }
        for (Neo4j server : new Neo4j[] {offServer, clockServer}) {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    void offWritesNothingAndRendersNothingUntilTheLevelIsSwitchedOn() {
        String success = "RETURN 'succeeded while off' AS text";
        String failure = "MATCH (n) RETURN failedWhileOff";
        try (Session session = offDriver.session()) {
            session.run(success).consume();
            assertThatThrownBy(() -> session.run(failure).consume()).isInstanceOf(ClientException.class);
        }
        String sentinel = QueryLogFile.sentinelQuery();
        try (Session session = clockDriver.session()) {
            session.run(sentinel).consume();
        }
        clockQueryLog.awaitEntryContaining(sentinel);

        assertThat(offQueryLog.entries())
                .as("no entry at all while OFF, the server's own start-up queries included")
                .isEmpty();
        assertThat(RENDERED_ENTRIES)
                .as("the counting renderer was never asked for an entry")
                .hasValue(0);

        QueryLogServers.setDynamic(offServer, GraphDatabaseSettings.log_queries, LogQueryLevel.VERBOSE);
        String shown = "RETURN 'shown once switched on' AS text";
        try (Session session = offDriver.session()) {
            session.run(shown).consume();
        }

        offQueryLog.awaitEntryContaining(shown);
        assertThat(offQueryLog.entriesContaining(success))
                .as("what ran while OFF is never written afterwards")
                .isEmpty();
        assertThat(offQueryLog.entriesContaining(failure)).isEmpty();
        assertThat(RENDERED_ENTRIES)
                .as("the counting renderer renders once the level is on, which proves it was wired all along")
                .hasPositiveValue();
    }

    /** A second query log, built like the real one, whose renderer counts the entries it is asked for. */
    private static final class CountingRendererQueryLogFactory extends ExtensionFactory<Neo4jDependencies> {

        CountingRendererQueryLogFactory() {
            super(ExtensionType.GLOBAL, "counting-renderer-query-log");
        }

        @Override
        public Lifecycle newInstance(ExtensionContext context, Neo4jDependencies dependencies) {
            return QueryLogExtensionFactory.lifecycleOf(dependencies, (entry, format) -> {
                RENDERED_ENTRIES.incrementAndGet();
                return RenderedEntry.of(entry, format);
            });
        }
    }
}
