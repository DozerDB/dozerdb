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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.GraphDatabaseSettings.LogQueryLevel;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListenerAdapter;
import org.neo4j.harness.Neo4j;

/**
 * Transaction entries reach the file under {@code db.logs.query.transaction.enabled} and
 * {@code db.logs.query.transaction.threshold}, both changed at runtime through {@code CALL dbms.setConfigValue}, the
 * manual's way, without a restart. Off by default, as on Neo4j, so enabling the query log alone adds no
 * transaction entry; at {@code VERBOSE} every transaction's start and end; at {@code INFO} only the ends that stayed
 * open at least the threshold, and every rollback caused by a failure whatever the threshold. The transaction id is
 * the one the queries run inside the transaction carry, so a transaction's statements can be reconstructed. The tests
 * run in order because the first one proves the default, before any level has been set.
 */
@TestMethodOrder(OrderAnnotation.class)
class TransactionEntriesIT {

    private static final Duration THRESHOLD = Duration.ofSeconds(1);

    /** What the commit-refusing listener throws; the rollback's reason in the entry. */
    private static final String REFUSAL = "commit refused on purpose";

    /** Transactions that create a node with this label are refused at commit by the test's listener. */
    private static final Label POISON = Label.label("Poison");

    /** The shipped layout around a transaction entry; the user segment is empty because authentication is off. */
    private static final Pattern TRANSACTION_ENTRY =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000 (?<level>INFO |ERROR) "
                    + "Transaction (?<event>started|committed|rolled back): transaction id:(?<id>\\d+) - (?<open>\\d+) ms - "
                    + "bolt-session\tbolt\tneo4j-java/[^\t]*\t\tclient/[^\t]+\tserver/[^\t]+>"
                    + "\t(?<database>[^ ]+) -  - (?<annotations>\\{[^}]*\\})(?<reason> - .*)?$");

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
        neo4j.databaseManagementService().registerTransactionEventListener("neo4j", new CommitRefusingListener());
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
    @Order(1)
    void theDefaultServerWritesNoTransactionEntry() {
        assertThat(neo4j.config().get(GraphDatabaseSettings.log_queries_transactions_level))
                .as("Neo4j's default for db.logs.query.transaction.enabled")
                .isEqualTo(LogQueryLevel.OFF);
        String marker = marker("off-by-default");
        commitTransaction(marker, "RETURN 'a transaction while transaction entries are off' AS text");
        runSentinel();

        assertThat(queryLog.entries())
                .as("no transaction entry at all, the server's own start-up transactions included")
                .noneMatch(entry -> entry.contains("Transaction "));
        assertThat(queryLog.entriesContaining(marker))
                .as("the query inside the transaction is logged as usual")
                .isNotEmpty();
    }

    @Test
    @Order(2)
    void atVerboseAnExplicitTransactionYieldsStartedAndCommittedEntriesSharingTheIdWithItsQuery() {
        setTransactionLevel(LogQueryLevel.VERBOSE, Duration.ZERO);
        String marker = marker("verbose-commit");
        String query = "RETURN 'inside the committed transaction' AS text";
        commitTransaction(marker, query);

        Matcher committed = entryOf("committed", marker);
        String transactionId = committed.group("id");
        assertThat(committed.group("level")).isEqualTo("INFO ");
        assertThat(committed.group("database")).isEqualTo("neo4j");
        assertThat(committed.group("annotations")).isEqualTo("{app: '" + marker + "'}");
        assertThat(committed.group("reason")).isNull();

        List<String> started =
                queryLog.entriesContaining("Transaction started: transaction id:" + transactionId + " - ");
        assertThat(started).as("one start entry for the transaction").hasSize(1);
        Matcher start = TRANSACTION_ENTRY.matcher(started.get(0));
        assertThat(start.matches())
                .as("the start entry's grammar: %s", started.get(0))
                .isTrue();
        assertThat(start.group("open")).isEqualTo("0");
        assertThat(start.group("annotations"))
                .as("Neo4j attaches the metadata after it reports the start")
                .isEqualTo("{}");

        assertThat(queryLog.endEntriesContaining(query))
                .as("the query entry carries the same transaction id")
                .hasSize(1)
                .allMatch(entry -> entry.contains(" - transaction id:" + transactionId + " - "));
    }

    @Test
    @Order(3)
    void atVerboseEveryDatabasesTransactionsAreWrittenTheSystemDatabasesIncluded() {
        setTransactionLevel(LogQueryLevel.VERBOSE, Duration.ZERO);
        String marker = marker("verbose-system");
        TransactionConfig metadata =
                TransactionConfig.builder().withMetadata(Map.of("app", marker)).build();
        try (Session session = driver.session(SessionConfig.forDatabase("system"));
                Transaction tx = session.beginTransaction(metadata)) {
            tx.run("SHOW DATABASES").consume();
            tx.commit();
        }

        assertThat(entryOf("committed", marker).group("database")).isEqualTo("system");
    }

    @Test
    @Order(4)
    void atVerboseARolledBackTransactionYieldsTheRolledBackEntryWithoutAReason() {
        setTransactionLevel(LogQueryLevel.VERBOSE, Duration.ZERO);
        String marker = marker("verbose-rollback");
        TransactionConfig metadata =
                TransactionConfig.builder().withMetadata(Map.of("app", marker)).build();
        try (Session session = driver.session();
                Transaction tx = session.beginTransaction(metadata)) {
            tx.run("CREATE (:Discarded)").consume();
            tx.rollback();
        }

        Matcher rolledBack = entryOf("rolled back", marker);
        assertThat(rolledBack.group("level")).isEqualTo("INFO ");
        assertThat(rolledBack.group("reason"))
                .as("a client's rollback has no failure")
                .isNull();
    }

    @Test
    @Order(5)
    void atInfoWithAThresholdAShortTransactionIsHiddenAndALongOneIsWrittenWithoutAStartEntry()
            throws InterruptedException {
        setTransactionLevel(LogQueryLevel.INFO, THRESHOLD);
        String shortMarker = marker("info-short");
        String longMarker = marker("info-long");
        commitTransaction(shortMarker, "RETURN 'short, under the threshold' AS text");
        TransactionConfig metadata = TransactionConfig.builder()
                .withMetadata(Map.of("app", longMarker))
                .build();
        try (Session session = driver.session();
                Transaction tx = session.beginTransaction(metadata)) {
            tx.run("RETURN 'long, over the threshold' AS text").consume();
            // The open time is Neo4j's clock from the transaction's start to its commit; sleeping at least the
            // threshold between the two keeps the entry independent of how fast the machine is.
            Thread.sleep(THRESHOLD.plusMillis(200).toMillis());
            tx.commit();
        }

        Matcher committed = entryOf("committed", longMarker);
        assertThat(Long.parseLong(committed.group("open"))).isGreaterThanOrEqualTo(THRESHOLD.toMillis());
        assertThat(queryLog.entriesContaining("Transaction started: transaction id:" + committed.group("id") + " - "))
                .as("no start entry at INFO")
                .isEmpty();
        // The short transaction committed before the long one began, on the same connection, so its entry would
        // precede the long one's: the long entry is the sentinel.
        assertThat(queryLog.entriesContaining("Transaction committed: ").stream()
                        .filter(entry -> entry.contains(shortMarker)))
                .as("the short transaction is hidden by the threshold")
                .isEmpty();
    }

    @Test
    @Order(6)
    void atInfoARollbackCausedByAFailureIsWrittenAtErrorWithTheReasonAndATraceWhateverTheThreshold() {
        setTransactionLevel(LogQueryLevel.INFO, THRESHOLD);
        String marker = marker("info-refused");
        TransactionConfig metadata =
                TransactionConfig.builder().withMetadata(Map.of("app", marker)).build();
        try (Session session = driver.session();
                Transaction tx = session.beginTransaction(metadata)) {
            tx.run("CREATE (:Poison)").consume();
            assertThatThrownBy(tx::commit).isInstanceOf(Neo4jException.class);
        }

        String entry = queryLog.awaitEntryWhere(
                "a rolled-back entry annotated with " + marker,
                candidate -> candidate.contains("Transaction rolled back: ") && candidate.contains(marker));
        List<String> lines = List.of(entry.split("\n"));
        Matcher rolledBack = TRANSACTION_ENTRY.matcher(lines.get(0));
        assertThat(rolledBack.matches())
                .as("the rolled-back entry's grammar: %s", lines.get(0))
                .isTrue();
        assertThat(rolledBack.group("level")).isEqualTo("ERROR");
        assertThat(rolledBack.group("reason")).isEqualTo(" - " + REFUSAL);
        assertThat(lines)
                .as("the stack trace follows the line: %s", entry)
                .anyMatch(line -> line.startsWith("java.lang.IllegalStateException: " + REFUSAL))
                .anyMatch(line -> line.matches("\\s+at org\\.neo4j\\..*"));
    }

    /** The transaction entry of {@code event} whose annotation data carries {@code marker}, once it is in the file. */
    private static Matcher entryOf(String event, String marker) {
        String entry = queryLog.awaitEntryWhere(
                "a 'Transaction " + event + ": ' entry annotated with " + marker,
                candidate -> candidate.contains("Transaction " + event + ": ") && candidate.contains(marker));
        Matcher matcher = TRANSACTION_ENTRY.matcher(entry.lines().findFirst().orElseThrow());
        assertThat(matcher.matches())
                .as("the %s entry's grammar: %s", event, entry)
                .isTrue();
        assertThat(matcher.group("event")).isEqualTo(event);
        return matcher;
    }

    private static String marker(String test) {
        return test + "-" + UUID.randomUUID();
    }

    /** Runs {@code query} in an explicit transaction whose metadata is {@code {app: marker}} and commits it. */
    private static void commitTransaction(String marker, String query) {
        TransactionConfig metadata =
                TransactionConfig.builder().withMetadata(Map.of("app", marker)).build();
        try (Session session = driver.session();
                Transaction tx = session.beginTransaction(metadata)) {
            tx.run(query).consume();
            tx.commit();
        }
    }

    private static void runSentinel() {
        String sentinel = QueryLogFile.sentinelQuery();
        try (Session session = driver.session()) {
            session.run(sentinel).consume();
        }
        queryLog.awaitEntryContaining(sentinel);
    }

    /** Changes the two transaction settings the manual's way, through {@code dbms.setConfigValue} over Bolt. */
    private static void setTransactionLevel(LogQueryLevel level, Duration threshold) {
        try (Session session = driver.session()) {
            session.run(
                            "CALL dbms.setConfigValue('db.logs.query.transaction.enabled', $value)",
                            Map.of("value", level.name()))
                    .consume();
            session.run(
                            "CALL dbms.setConfigValue('db.logs.query.transaction.threshold', $value)",
                            Map.of("value", threshold.toMillis() + "ms"))
                    .consume();
        }
    }

    /** Refuses the commit of any transaction that created a {@code Poison} node, so Neo4j rolls it back with a failure. */
    private static final class CommitRefusingListener extends TransactionEventListenerAdapter<Object> {
        @Override
        public Object beforeCommit(TransactionData data, org.neo4j.graphdb.Transaction tx, GraphDatabaseService db) {
            for (Node node : data.createdNodes()) {
                if (node.hasLabel(POISON)) {
                    throw new IllegalStateException(REFUSAL);
                }
            }
            return null;
        }
    }
}
