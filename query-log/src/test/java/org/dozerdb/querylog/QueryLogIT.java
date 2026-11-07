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
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * With no logging configuration at all, a DozerDB writes Neo4j's query log: one plain end entry per query in
 * {@code <logs>/query.log}, in the shipped pattern layout, attributed to the database the query ran on, and exactly
 * once whichever database that is (one listener on the global bus hears every database once).
 */
class QueryLogIT {

    /** The shipped layout, {@code %d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n}, around the manual's end-entry grammar. */
    private static final Pattern PLAIN_END_ENTRY = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000 INFO  "
                    + "id:(?<id>\\d+) - transaction id:\\d+ - \\d+ ms: \\(planning: \\d+, waiting: \\d+\\) - \\d+ B"
                    + " - \\d+ page hits, \\d+ page faults - bolt-session\tbolt\tneo4j-java/[^\t]*\t\tclient/[^\t]+\tserver/[^\t]+>"
                    + "\t(?<database>[^ ]+) - (?<user>[^ ]*) - (?<query>.*) - \\{\\} - runtime=(?<runtime>[a-z]+) - \\{.*\\}$");

    /** The start entry: the end grammar behind {@code Query started: } with zeroed values. */
    private static final Pattern START_ENTRY =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000 INFO  Query started: "
                    + "id:(?<id>\\d+) - transaction id:\\d+ - 0 ms: \\(planning: 0, waiting: 0\\) - 0 B"
                    + " - 0 page hits, 0 page faults - bolt-session\tbolt\tneo4j-java/[^\t]*\t\tclient/[^\t]+\tserver/[^\t]+>"
                    + "\t(?<database>[^ ]+) - (?<user>[^ ]*) - (?<query>.*) - \\{\\} - runtime=null - \\{.*\\}$");

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
    void aBoltQueryWritesOnePlainEndEntryInTheShippedPattern() {
        String query = "RETURN 'first entry' AS text";
        try (Session session = driver.session()) {
            session.run(query).consume();
        }
        String sentinel = runSentinel("neo4j");

        queryLog.awaitEntryContaining(query);
        assertThat(queryLog.endEntriesContaining(query))
                .as("exactly one end entry after the sentinel")
                .hasSize(1);
        String entry = queryLog.endEntriesContaining(query).get(0);
        var matcher = PLAIN_END_ENTRY.matcher(entry);
        assertThat(matcher.matches())
                .as("entry matches the end-entry grammar: %s", entry)
                .isTrue();
        assertThat(matcher.group("database")).isEqualTo("neo4j");
        assertThat(matcher.group("query")).isEqualTo(query);
        assertThat(matcher.group("runtime")).isNotEqualTo("null");
        assertThat(queryLog.endEntriesContaining(sentinel)).hasSize(1);
    }

    @Test
    void aQueryOnASecondDatabaseAppearsOnceWithThatDatabase() {
        createDatabase("second");
        String query = "RETURN 'on the second database' AS text";
        try (Session session = driver.session(SessionConfig.forDatabase("second"))) {
            session.run(query).consume();
        }
        runSentinel("second");

        queryLog.awaitEntryContaining(query);
        assertThat(queryLog.endEntriesContaining(query)).hasSize(1);
        String entry = queryLog.endEntriesContaining(query).get(0);
        var matcher = PLAIN_END_ENTRY.matcher(entry);
        assertThat(matcher.matches())
                .as("entry matches the end-entry grammar: %s", entry)
                .isTrue();
        assertThat(matcher.group("database")).isEqualTo("second");
    }

    @Test
    void aSuccessfulQueryAtTheDefaultVerboseWritesAStartEntryThenTheEndEntryWithTheSameId() {
        String query = "RETURN 'started and ended' AS text";
        try (Session session = driver.session()) {
            session.run(query).consume();
        }
        runSentinel("neo4j");

        queryLog.awaitEntryContaining(query);
        var entries = queryLog.entriesContaining(query);
        assertThat(entries).as("one start entry and one end entry: %s", entries).hasSize(2);
        var start = START_ENTRY.matcher(entries.get(0));
        assertThat(start.matches())
                .as("the first entry is the start entry: %s", entries.get(0))
                .isTrue();
        var end = PLAIN_END_ENTRY.matcher(entries.get(1));
        assertThat(end.matches())
                .as("the second entry is the end entry: %s", entries.get(1))
                .isTrue();
        assertThat(end.group("id")).isEqualTo(start.group("id"));
        assertThat(start.group("query")).isEqualTo(query);
    }

    @Test
    void aFailingQueryWritesAnErrorEntryEndingWithTheReasonFollowedByAStackTrace() {
        String query = "MATCH (n) RETURN missingVariable";
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
        }
        runSentinel("neo4j");

        String entry = queryLog.awaitEntryContaining(" ERROR id:");
        List<String> lines = List.of(entry.split("\n"));
        assertThat(lines.get(0))
                .as("the failed-query line ends with the reason: %s", lines.get(0))
                .matches(".* ERROR id:\\d+ - transaction id:-?\\d+ - .* - " + Pattern.quote(query)
                        + " - \\{\\} - runtime=null - \\{\\} - Variable `missingVariable` not defined.*");
        // Neo4j's message continues over the next lines (the query and a caret); the stack trace follows them.
        assertThat(lines)
                .as("a stack trace follows the line: %s", entry)
                .anyMatch(line -> line.contains("Exception: Variable `missingVariable` not defined"))
                .anyMatch(line -> line.matches("\\s+at org\\.neo4j\\..*"));
        assertThat(queryLog.endEntriesContaining(query))
                .as("one failed-query entry")
                .hasSize(1);
        // Neo4j fires startExecution only once a query is parsed, so a query that fails to compile has no start entry
        // at VERBOSE unless early_raw_logging_enabled moves it to startProcessing (EarlyRawLoggingIT).
        assertThat(queryLog.startEntriesContaining(query)).isEmpty();
    }

    /** Runs a sentinel query on {@code database} and waits for its entry, so that absences can be judged. */
    private static String runSentinel(String database) {
        String sentinel = QueryLogFile.sentinelQuery();
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.run(sentinel).consume();
        }
        queryLog.awaitEntryContaining(sentinel);
        return sentinel;
    }

    /** DozerDB does not accept {@code CREATE DATABASE ... WAIT}; the test polls until the database is online. */
    private static void createDatabase(String name) {
        try (Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.run("CREATE DATABASE " + name).consume();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (true) {
                boolean online = session.run("SHOW DATABASE " + name + " YIELD currentStatus").stream()
                        .anyMatch(
                                record -> record.get("currentStatus").asString().equals("online"));
                if (online) {
                    return;
                }
                assertThat(Instant.now())
                        .as("database " + name + " comes online")
                        .isBefore(deadline);
                Thread.onSpinWait();
            }
        }
    }
}
