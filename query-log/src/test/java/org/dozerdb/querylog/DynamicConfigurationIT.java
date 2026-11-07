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

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.GraphDatabaseSettings.LogQueryLevel;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * {@code db.logs.query.enabled} and {@code db.logs.query.threshold} reach the file and change at runtime without a
 * restart, as the Operations Manual says they do (both are dynamic); the change is made on the kernel's live
 * configuration, which is what Enterprise's {@code dbms.setConfigValue} does and DozerDB has no procedure for. At
 * {@code INFO} a success entry is written only when the query took at least the threshold, a failure always, and
 * never a start entry or the {@code id:} prefix, which are {@code VERBOSE}'s. Every test sets the levels it needs
 * first, so the tests run in any order on one server.
 */
class DynamicConfigurationIT {

    private static final Duration THRESHOLD = Duration.ofSeconds(1);

    /** How long the slow query is made to wait on a lock: the threshold plus a margin for the connection set-up. */
    private static final Duration SLOW_QUERY_WAIT = THRESHOLD.plusSeconds(1);

    private static final Pattern INFO_END_ENTRY =
            Pattern.compile("^\\S+ \\S+ INFO  transaction id:\\d+ - (?<elapsed>\\d+) ms: .*");

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
        queryLog = QueryLogFile.of(neo4j);
        run("CREATE (:Lock {name: 'slow query'})");
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
    void atInfoWithAThresholdAFastQueryIsHiddenAndASlowOneIsWrittenWithoutTheIdPrefix() throws InterruptedException {
        setLevel(LogQueryLevel.INFO, THRESHOLD);
        String fast = "RETURN 'fast, under the threshold' AS text";
        String slow = "MATCH (n:Lock {name: 'slow query'}) SET n.waited = true RETURN 'slow, over the threshold'";
        run(fast);
        runHeldOnALockFor(SLOW_QUERY_WAIT, slow);
        runFailingSentinel();

        String entry = queryLog.awaitEntryContaining(slow);
        Matcher end = INFO_END_ENTRY.matcher(entry);
        assertThat(end.matches())
                .as("the slow query's end entry has no id: prefix at INFO: %s", entry)
                .isTrue();
        assertThat(Long.parseLong(end.group("elapsed"))).isGreaterThanOrEqualTo(THRESHOLD.toMillis());
        assertThat(queryLog.startEntriesContaining(slow))
                .as("no start entry at INFO")
                .isEmpty();
        assertThat(queryLog.entriesContaining(fast))
                .as("the fast query is hidden by the threshold")
                .isEmpty();
    }

    @Test
    void atInfoWithThresholdZeroASubMillisecondQueryIsWritten() {
        setLevel(LogQueryLevel.INFO, Duration.ZERO);
        String query = "RETURN 'sub-millisecond, at threshold zero' AS text";
        run(query);

        String entry = queryLog.awaitEntryContaining(query);
        assertThat(entry).as("written at INFO without the id: prefix").matches(INFO_END_ENTRY);
    }

    @Test
    void atInfoAFailureIsWrittenWhateverTheThreshold() {
        setLevel(LogQueryLevel.INFO, THRESHOLD);
        String query = "MATCH (n) RETURN failsFastAtInfo";
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
        }

        String entry = queryLog.awaitEntryContaining(query);
        assertThat(entry.lines().findFirst().orElseThrow())
                .as("a failed-query entry at ERROR, without the id: prefix: %s", entry)
                .matches(
                        "^\\S+ \\S+ ERROR transaction id:-?\\d+ - \\d+ ms: .* - Variable `failsFastAtInfo` not defined.*");
    }

    @Test
    void theDetailLevelChangesForTheNextQueryWithoutARestart() {
        setLevel(LogQueryLevel.INFO, THRESHOLD);
        String hidden = "RETURN 'hidden before the level changed' AS text";
        run(hidden);
        runFailingSentinel();
        assertThat(queryLog.entriesContaining(hidden)).isEmpty();

        setLevel(LogQueryLevel.VERBOSE, THRESHOLD);
        String shown = "RETURN 'shown once the level changed' AS text";
        run(shown);

        queryLog.awaitEntryContaining(shown);
        assertThat(queryLog.startEntriesContaining(shown))
                .as("VERBOSE writes the start entry")
                .hasSize(1);
        assertThat(queryLog.endEntriesContaining(shown))
                .as("VERBOSE writes the end entry with the id: prefix, the threshold ignored")
                .hasSize(1)
                .allMatch(entry -> entry.contains(" INFO  id:"));
    }

    @Test
    void theThresholdChangesForTheNextQueryWithoutARestart() {
        setLevel(LogQueryLevel.INFO, THRESHOLD);
        String hidden = "RETURN 'hidden before the threshold changed' AS text";
        run(hidden);
        runFailingSentinel();
        assertThat(queryLog.entriesContaining(hidden)).isEmpty();

        setThreshold(Duration.ZERO);
        String shown = "RETURN 'shown once the threshold changed' AS text";
        run(shown);

        assertThat(queryLog.awaitEntryContaining(shown)).matches(INFO_END_ENTRY);
    }

    private static void setLevel(LogQueryLevel level, Duration threshold) {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries, level);
        setThreshold(threshold);
    }

    private static void setThreshold(Duration threshold) {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_threshold, threshold);
    }

    private static void run(String query) {
        try (Session session = driver.session()) {
            session.run(query).consume();
        }
    }

    /** A failure is written at every level but OFF, so it can stand as the sentinel where a success would be hidden. */
    private static void runFailingSentinel() {
        String sentinel = QueryLogFile.failingSentinelQuery();
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(sentinel).consume()).isInstanceOf(ClientException.class);
        }
        queryLog.awaitEntryContaining(sentinel);
    }

    /**
     * Runs {@code query}, which writes the {@code Lock} node, while another transaction holds that node's lock for
     * {@code wait}: the query's elapsed time is at least the wait, measured by Neo4j's own clock, with no reliance on
     * how fast the machine is.
     */
    private static void runHeldOnALockFor(Duration wait, String query) throws InterruptedException {
        try (Session holder = driver.session();
                Transaction lock = holder.beginTransaction()) {
            lock.run("MATCH (n:Lock {name: 'slow query'}) SET n.held = true").consume();
            Thread waiter = new Thread(() -> run(query), "query waiting on the lock");
            waiter.start();
            Thread.sleep(wait.toMillis());
            lock.commit();
            waiter.join();
        }
    }
}
