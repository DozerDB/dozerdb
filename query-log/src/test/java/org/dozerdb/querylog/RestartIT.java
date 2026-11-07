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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.harness.Neo4j;

/**
 * {@code shutdown()} removes both listeners, so a server restarted in the same home registers them once and
 * every query of the second life is logged exactly once, appended to the {@code query.log} of the first.
 */
class RestartIT {

    @Test
    void aRestartedServerLogsEachQueryOnce(@TempDir Path home) {
        String firstLife = "RETURN 'first life' AS text";
        String secondLife = "RETURN 'second life' AS text";

        runAndAwait(home, firstLife);
        runAndAwait(home, secondLife);

        QueryLogFile queryLog = QueryLogFile.inHome(home);
        assertThat(queryLog.endEntriesContaining(firstLife)).hasSize(1);
        assertThat(queryLog.endEntriesContaining(secondLife)).hasSize(1);
        assertThat(queryLog.endEntriesContaining("sentinel-")).hasSize(2);
    }

    /** One life of the server: start in the home, run the query and a sentinel, wait for both, close. */
    private static void runAndAwait(Path home, String query) {
        Neo4j neo4j = QueryLogServers.startWithBoltInHome(home);
        try (Driver driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())) {
            String sentinel = QueryLogFile.sentinelQuery();
            try (Session session = driver.session()) {
                session.run(query).consume();
                session.run(sentinel).consume();
            }
            QueryLogFile.of(neo4j).awaitEntryContaining(sentinel);
        } finally {
            neo4j.close();
        }
    }
}
