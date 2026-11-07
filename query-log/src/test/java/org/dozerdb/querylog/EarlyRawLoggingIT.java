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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * {@code db.logs.query.early_raw_logging_enabled} moves the start entry to the moment the query is received, before
 * it is parsed or obfuscated, so that a query that fails in parsing is still recorded with its raw text and
 * parameters; the end entry keeps the obfuscation rules. The early start entry has one shape on every path: the
 * zeroed end grammar with {@code transaction id:0} and the database {@code <none>}, because the query has reached
 * neither yet. Off (the default) the start entry stays where Neo4j announces the planned query, with the transaction
 * id, the database and the obfuscated text. The setting is dynamic, so every test sets it and restores the default.
 */
class EarlyRawLoggingIT {

    /** The early start entry: the start grammar with {@code transaction id:0} and {@code <none>}. */
    private static final Pattern EARLY_START_ENTRY = Pattern.compile(
            "^\\S+ \\S+ INFO  Query started: id:(?<id>\\d+) - transaction id:0 - 0 ms: \\(planning: 0, waiting: 0\\)"
                    + " - 0 B - 0 page hits, 0 page faults - bolt-session\tbolt\tneo4j-java/[^\t]*\t\tclient/[^\t]+\tserver/[^\t]+>"
                    + "\t<none> - (?<user>[^ ]*) - (?<query>.*) - (?<parameters>\\{.*\\}) - runtime=null - \\{.*\\}$");

    /** The start entry written once the query is planned: a real transaction id and the database. */
    private static final Pattern PLANNED_START_ENTRY = Pattern.compile(
            "^\\S+ \\S+ INFO  Query started: id:(?<id>\\d+) - transaction id:(?<transaction>[1-9]\\d*) - 0 ms: .*"
                    + "\t(?<database>neo4j|system) - (?<user>[^ ]*) - (?<query>.*) - (?<parameters>\\{.*\\}) - runtime=null - \\{.*\\}$");

    private static final Pattern END_ENTRY = Pattern.compile(
            "^\\S+ \\S+ (?<level>INFO |ERROR) id:(?<id>\\d+) - transaction id:(?<transaction>-?\\d+) - \\d+ ms: .*"
                    + "\t(?<database>\\S+) - (?<user>[^ ]*) - (?<query>.*?) - (?<parameters>\\{.*?\\}) - runtime=(?<runtime>[a-z]+) - \\{.*?\\}(?<reason> - .*)?$",
            Pattern.DOTALL);

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
        queryLog = QueryLogFile.of(neo4j);
    }

    @AfterEach
    void restoreTheDefaults() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_early_raw_logging_enabled, false);
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, false);
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
    void aQueryThatFailsToParseHasAnEarlyStartEntryWithItsRawTextAndTheFailedEntryWithTheSameId() {
        earlyRawLogging(true);
        String query = "RETURN early_raw_parse_failure_" + token() + " (";
        runFailing(query, Map.of("p", "raw parameter"));

        String start = queryLog.awaitEntryWhere(
                "the start entry of " + query,
                entry -> entry.contains(QueryLogFile.START_PREFIX) && entry.contains(query));
        Matcher early = EARLY_START_ENTRY.matcher(start);
        assertThat(early.matches())
                .as("the early start entry has transaction id:0 and <none>: %s", start)
                .isTrue();
        assertThat(early.group("query")).isEqualTo(query);
        assertThat(early.group("parameters")).isEqualTo("{p: 'raw parameter'}");

        String end = queryLog.awaitEntryWhere(
                "the failed-query entry of " + query, entry -> entry.contains(" ERROR ") && entry.contains(query));
        Matcher failed = END_ENTRY.matcher(end);
        assertThat(failed.matches()).as("the failed-query entry: %s", end).isTrue();
        assertThat(failed.group("id")).isEqualTo(early.group("id"));
        assertThat(failed.group("transaction"))
                .as("Neo4j's own value for a query never bound")
                .isEqualTo("-1");
        assertThat(failed.group("database")).isEqualTo("<none>");
        assertThat(queryLog.startEntriesContaining(query)).hasSize(1);
    }

    @Test
    void aSuccessfulQueryHasExactlyOneStartEntryTheEarlyOneAndOneEndEntryWithTheSameId() {
        earlyRawLogging(true);
        String query = "RETURN 'early-success-" + token() + "' AS text";
        run(query, Map.of());
        runSentinel();

        List<String> entries = queryLog.entriesContaining(query);
        assertThat(entries).as("one start entry and one end entry: %s", entries).hasSize(2);
        Matcher start = EARLY_START_ENTRY.matcher(entries.get(0));
        assertThat(start.matches())
                .as("the first entry is the early start entry: %s", entries.get(0))
                .isTrue();
        Matcher end = END_ENTRY.matcher(entries.get(1));
        assertThat(end.matches())
                .as("the second entry is the end entry: %s", entries.get(1))
                .isTrue();
        assertThat(end.group("id")).isEqualTo(start.group("id"));
        assertThat(end.group("level")).isEqualTo("INFO ");
        assertThat(end.group("database")).isEqualTo("neo4j");
        assertThat(Long.parseLong(end.group("transaction"))).isPositive();
    }

    @Test
    void underObfuscateLiteralsTheEarlyStartEntryIsRawAndTheEndEntryIsObfuscated() {
        earlyRawLogging(true);
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, true);
        String literal = "early-literal-" + token();
        String query = "RETURN '" + literal + "' AS text, $p AS parameter";
        run(query, Map.of("p", "parameter value"));

        String start = queryLog.awaitEntryContaining(literal);
        assertThat(start)
                .as("the early start entry carries the literal and the parameter")
                .matches(EARLY_START_ENTRY)
                .contains(" - " + query + " - {p: 'parameter value'} - runtime=null - ");
        String end = queryLog.awaitEntryContaining("RETURN ****** AS text, $p AS parameter");
        assertThat(end)
                .as("the end entry follows the obfuscation rules: literals masked, parameters not")
                .matches(END_ENTRY)
                .contains(" - RETURN ****** AS text, $p AS parameter - {p: 'parameter value'} - runtime=")
                .doesNotContain(literal);
        assertThat(queryLog.endEntriesContaining(literal))
                .as("no end entry shows the literal")
                .isEmpty();
    }

    @Test
    void underObfuscateLiteralsAQueryThatFailsToParseKeepsItsRawTextInTheEarlyStartEntryOnly() {
        earlyRawLogging(true);
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, true);
        String query = "RETURN 'withheld-" + token() + "' (";
        runFailing(query, Map.of());

        String start = queryLog.awaitEntryContaining(query);
        assertThat(start).matches(EARLY_START_ENTRY);
        String id = idOf(start);
        String end = queryLog.awaitEntryWhere(
                "the failed-query entry id:" + id, entry -> entry.contains(" ERROR id:" + id + " - "));
        // The segment is empty; the stack trace that follows the line is Neo4j's exception, which quotes the query.
        assertThat(end.lines().findFirst().orElseThrow())
                .as("the end entry withholds the text of a query that could not be parsed: %s", end)
                .doesNotContain(query)
                .contains("\t<none> -  -  - {} - runtime=null - {} - ");
    }

    @Test
    void aPasswordReachesTheEarlyStartEntryRawWhichIsWhatTheSettingIsDocumentedToDo() {
        earlyRawLogging(true);
        String password = "topsecret-" + token();
        String statement = "CREATE USER early_" + token() + " SET PASSWORD '" + password + "' CHANGE NOT REQUIRED";
        run(statement, Map.of(), "system");

        String start = queryLog.awaitEntryContaining(password);
        assertThat(start).as("the manual: logged without obfuscating passwords").matches(EARLY_START_ENTRY);
        String end = queryLog.awaitEntryContaining(statement.replace("'" + password + "'", "******"));
        assertThat(end).matches(END_ENTRY);
        assertThat(queryLog.endEntriesContaining(password))
                .as("the end entry masks the password as always")
                .isEmpty();
    }

    @Test
    void offTheStartEntryIsWrittenOncePlannedWithTheTransactionIdTheDatabaseAndNoneForAParseFailure() {
        earlyRawLogging(true);
        String early = "RETURN 'early-before-the-change-" + token() + "' AS text";
        run(early, Map.of());
        queryLog.awaitEntryWhere("the early start", e -> e.contains(QueryLogFile.START_PREFIX) && e.contains(early));

        earlyRawLogging(false);
        String planned = "RETURN 'planned-after-the-change-" + token() + "' AS text";
        run(planned, Map.of());
        String failing = "RETURN planned_parse_failure_" + token() + " (";
        runFailing(failing, Map.of());
        runSentinel();

        List<String> starts = queryLog.startEntriesContaining(planned);
        assertThat(starts).as("exactly one start entry, the planned one").hasSize(1);
        Matcher start = PLANNED_START_ENTRY.matcher(starts.get(0));
        assertThat(start.matches())
                .as("the start entry carries the transaction id and the database: %s", starts.get(0))
                .isTrue();
        assertThat(start.group("database")).isEqualTo("neo4j");
        assertThat(queryLog.startEntriesContaining(failing))
                .as("off, a query that fails to parse has no start entry")
                .isEmpty();
        assertThat(queryLog.endEntriesContaining(failing)).hasSize(1);
    }

    private static void earlyRawLogging(boolean enabled) {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_early_raw_logging_enabled, enabled);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String idOf(String startEntry) {
        Matcher matcher = EARLY_START_ENTRY.matcher(startEntry);
        assertThat(matcher.matches()).as("an early start entry: %s", startEntry).isTrue();
        return matcher.group("id");
    }

    private static void run(String query, Map<String, Object> parameters) {
        run(query, parameters, "neo4j");
    }

    private static void run(String query, Map<String, Object> parameters, String database) {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.run(query, parameters).consume();
        }
    }

    private static void runFailing(String query, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(query, parameters).consume()).isInstanceOf(ClientException.class);
        }
    }

    private static void runSentinel() {
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel, Map.of());
        queryLog.awaitEntryContaining(sentinel);
    }
}
