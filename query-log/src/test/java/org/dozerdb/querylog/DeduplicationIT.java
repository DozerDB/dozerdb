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
 * Neo4j 2026.10.1 reports a routed query whose failure is raised inside the Cypher engine's compile step twice
 * (the router's end monitor and the router's own catch block both call {@code endFailure}). A {@code USING INDEX} hint
 * that cannot be fulfilled, with {@code dbms.cypher.hints_error=true}, is such a failure. The query log writes one
 * failed-query entry for it.
 */
class DeduplicationIT {

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt(
                builder -> builder.withConfig(GraphDatabaseSettings.cypher_hints_error, true));
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
    void aRoutedQueryWhoseCompileFailsProducesOneFailedEntry() {
        String query = "MATCH (n:Unindexed) USING INDEX n:Unindexed(p) WHERE n.p = 1 RETURN n";
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
        }
        String sentinel = QueryLogFile.sentinelQuery();
        try (Session session = driver.session()) {
            session.run(sentinel).consume();
        }
        queryLog.awaitEntryContaining(sentinel);

        assertThat(queryLog.endEntriesContaining(query)).hasSize(1);
        assertThat(queryLog.endEntriesContaining(query).get(0))
                .contains(" ERROR id:")
                .contains("\tneo4j - ")
                .containsPattern(" - \\{\\} - runtime=null - \\{.*\\} - .*[Ii]ndex");
    }
}
