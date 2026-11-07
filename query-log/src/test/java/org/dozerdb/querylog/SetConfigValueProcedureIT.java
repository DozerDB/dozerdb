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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.harness.Neo4j;

/**
 * The manual's way of changing the detail level at runtime, {@code CALL dbms.setConfigValue}, reaches the query log:
 * an administrator over Bolt turns the level down and up again and the next query is logged accordingly, with no
 * restart. The procedure comes from DozerDB's procedures module, on this module's test classpath exactly as it is in
 * every packaged server; the query log itself only follows the live configuration.
 */
class SetConfigValueProcedureIT {

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
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
    void theProcedureChangesTheDetailLevelForTheNextQuery() {
        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'VERBOSE')");
        String atVerbose = "RETURN 'logged with a start entry before the procedure ran' AS text";
        run(atVerbose);
        queryLog.awaitEntryContaining(atVerbose);
        assertThat(queryLog.startEntriesContaining(atVerbose)).hasSize(1);

        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')");
        String atInfo = "RETURN 'logged without a start entry after the procedure ran' AS text";
        run(atInfo);
        queryLog.awaitEntryContaining(atInfo);
        assertThat(queryLog.startEntriesContaining(atInfo))
                .as("INFO writes no start entry")
                .isEmpty();
        assertThat(queryLog.endEntriesContaining(atInfo))
                .as("INFO writes the end entry without the id: prefix")
                .hasSize(1)
                .allMatch(entry -> entry.contains(" INFO  transaction id:"));

        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'OFF')");
        String atOff = "RETURN 'not logged once the procedure turned the log off' AS text";
        run(atOff);
        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'VERBOSE')");
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel);
        queryLog.awaitEntryContaining(sentinel);
        assertThat(queryLog.entriesContaining(atOff)).isEmpty();
    }

    private static void run(String query) {
        try (Session session = driver.session()) {
            session.run(query).consume();
        }
    }
}
