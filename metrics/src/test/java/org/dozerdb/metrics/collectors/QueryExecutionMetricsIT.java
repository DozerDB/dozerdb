/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The query execution metrics (Operations Manual 2026.10, "Query execution metrics") as an administrator observes
 * them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.<db>.db.query.execution.<metric>")}. Queries
 * run embedded and over Bolt (the Neo4j Java driver, through the query router) on the default database; the
 * metrics are read through the system database, so the reading query is never among the queries observed.
 */
class QueryExecutionMetricsIT {

    private static final String COUNT_NODES = "MATCH (n) RETURN count(n) AS nodes";
    private static final String DIVIDE_BY_ZERO = "UNWIND [0] AS divisor RETURN 1 / divisor AS quotient";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithBoltAndAnOpenFilter() {
        neo4j = MetricsServers.startWithBolt(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")));
        db = neo4j.defaultDatabaseService();
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    /** Community's default runtime is slotted, so a plain query counts in the overall and the slotted series. */
    @Test
    void everySuccessfulQueryIsCountedOnceAndItsLatencyRecordedInTheOverallAndTheSlottedSeries() {
        long success = count("success");
        long latencies = count("latency.millis");
        long slottedSuccess = count("slotted.success");
        long slottedLatencies = count("slotted.latency.millis");

        for (int i = 0; i < 5; i++) {
            run(COUNT_NODES);
        }

        assertThat(count("success")).as("success").isEqualTo(success + 5);
        assertThat(count("latency.millis")).as("latency samples").isEqualTo(latencies + 5);
        assertThat(count("slotted.success")).as("slotted.success").isEqualTo(slottedSuccess + 5);
        assertThat(count("slotted.latency.millis"))
                .as("slotted latency samples")
                .isEqualTo(slottedLatencies + 5);
    }

    /**
     * The double-counting guard. Neo4j fires {@code QueryExecutionMonitor} at two levels: a Bolt query starts on
     * the DBMS-level monitor of the query router and ends on the database's own monitor, whose events bubble up to
     * the DBMS-level bus; an embedded query is announced by the Cypher engine on the database's monitor alone. Each
     * of the fifteen queries below (auto-commit and explicit transactions over Bolt, embedded transactions) must
     * raise the success count and the latency samples by exactly one.
     */
    @Test
    void aQueryIsCountedOnceAlthoughTheExecutionMonitorFiresAtTheDbmsAndTheDatabaseLevel() {
        long success = count("success");
        long latencies = count("latency.millis");
        long slottedSuccess = count("slotted.success");

        try (Driver driver = driver();
                Session session = driver.session()) {
            for (int i = 0; i < 5; i++) {
                session.run(COUNT_NODES).consume();
            }
            try (org.neo4j.driver.Transaction tx = session.beginTransaction()) {
                for (int i = 0; i < 5; i++) {
                    tx.run(COUNT_NODES).consume();
                }
                tx.commit();
            }
        }
        for (int i = 0; i < 5; i++) {
            run(COUNT_NODES);
        }

        Await.until("fifteen queries are counted", () -> count("success") >= success + 15);
        assertThat(count("success")).as("success").isEqualTo(success + 15);
        assertThat(count("latency.millis")).as("latency samples").isEqualTo(latencies + 15);
        assertThat(count("slotted.success")).as("slotted.success").isEqualTo(slottedSuccess + 15);
    }

    /** A query that fails before it is compiled has no runtime, so only the overall failure series counts it. */
    @Test
    void aQueryThatFailsToParseIsCountedAsOneFailureInTheOverallSeriesOnly() {
        long failure = count("failure");
        long slottedFailure = count("slotted.failure");
        long success = count("success");
        long latencies = count("latency.millis");

        assertThatThrownBy(() -> run("THIS IS NOT CYPHER")).isInstanceOf(QueryExecutionException.class);

        assertThat(count("failure")).as("failure").isEqualTo(failure + 1);
        assertThat(count("slotted.failure")).as("slotted.failure").isEqualTo(slottedFailure);
        assertThat(count("success")).as("success").isEqualTo(success);
        assertThat(count("latency.millis")).as("latency samples").isEqualTo(latencies);
    }

    /** A query the slotted runtime compiled and that fails while running counts in both failure series. */
    @Test
    void aQueryThatFailsWhileRunningIsCountedAsOneFailureInTheOverallAndTheSlottedSeries() {
        long failure = count("failure");
        long slottedFailure = count("slotted.failure");
        long success = count("success");
        long latencies = count("latency.millis");

        assertThatThrownBy(() -> run(DIVIDE_BY_ZERO)).isInstanceOf(QueryExecutionException.class);

        assertThat(count("failure")).as("failure").isEqualTo(failure + 1);
        assertThat(count("slotted.failure")).as("slotted.failure").isEqualTo(slottedFailure + 1);
        assertThat(count("success")).as("success").isEqualTo(success);
        assertThat(count("latency.millis")).as("latency samples").isEqualTo(latencies);
    }

    /** Over Bolt the failing query ends on the database's monitor too, once. */
    @Test
    void aBoltQueryThatFailsWhileRunningIsCountedAsOneFailure() {
        long failure = count("failure");
        long slottedFailure = count("slotted.failure");
        long success = count("success");

        try (Driver driver = driver();
                Session session = driver.session()) {
            assertThatThrownBy(() -> session.run(DIVIDE_BY_ZERO).consume()).isInstanceOf(ClientException.class);
        }

        Await.until("the failing query is counted", () -> count("failure") >= failure + 1);
        assertThat(count("failure")).as("failure").isEqualTo(failure + 1);
        assertThat(count("slotted.failure")).as("slotted.failure").isEqualTo(slottedFailure + 1);
        assertThat(count("success")).as("success").isEqualTo(success);
    }

    /**
     * Over Bolt the query router parses a query before it opens the database transaction, so a parse error ends
     * on the DBMS-level monitor only, with no database to attribute it to: no database counts it. Pinned, so a
     * Neo4j upgrade that starts attributing it is noticed (inventory notes of the failure row).
     */
    @Test
    void aBoltQueryThatFailsToParseIsAttributedToNoDatabase() {
        long failure = count("failure");
        long success = count("success");
        long systemFailure = count("system", "failure");

        try (Driver driver = driver();
                Session session = driver.session()) {
            assertThatThrownBy(() -> session.run("THIS IS NOT CYPHER").consume())
                    .isInstanceOf(ClientException.class);
            session.run(COUNT_NODES).consume();
        }

        Await.until("the query after the parse error is counted", () -> count("success") >= success + 1);
        assertThat(count("failure")).as("failure of neo4j").isEqualTo(failure);
        assertThat(count("system", "failure")).as("failure of system").isEqualTo(systemFailure);
    }

    /** Each database counts its own queries: a session bound to another database leaves the default one alone. */
    @Test
    void theQueriesOfADatabaseAreCountedForThatDatabaseOnly() {
        String database = "queries";
        executeOnSystem("CREATE DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        long success = count("success");
        long databaseSuccess = count(database, "success");

        try (Driver driver = driver();
                Session session = driver.session(SessionConfig.forDatabase(database))) {
            for (int i = 0; i < 3; i++) {
                session.run(COUNT_NODES).consume();
            }
        }

        Await.until(
                "three queries are counted for " + database, () -> count(database, "success") >= databaseSuccess + 3);
        assertThat(count(database, "success")).as("success of " + database).isEqualTo(databaseSuccess + 3);
        assertThat(count("success")).as("success of neo4j").isEqualTo(success);

        executeOnSystem("DROP DATABASE " + database);
        awaitStatus(database, Optional.empty());
    }

    /**
     * Community has no pipelined and no parallel runtime ({@code CommunityRuntimeFactory}): a query that asks for
     * either is executed by the slotted runtime, so the two series are registered and stay at zero, as every
     * counter only an Enterprise mechanism drives.
     */
    @Test
    void thePipelinedAndParallelSeriesStayAtZeroBecauseCommunityFallsBackToSlotted() {
        long slottedSuccess = count("slotted.success");

        run("CYPHER runtime=pipelined " + COUNT_NODES);
        run("CYPHER runtime=parallel " + COUNT_NODES);
        assertThatThrownBy(() -> run("CYPHER runtime=pipelined " + DIVIDE_BY_ZERO))
                .isInstanceOf(QueryExecutionException.class);

        assertThat(count("slotted.success")).as("slotted.success").isEqualTo(slottedSuccess + 2);
        for (String runtime : new String[] {"pipelined", "parallel"}) {
            assertThat(count(runtime + ".success")).as(runtime + ".success").isZero();
            assertThat(count(runtime + ".failure")).as(runtime + ".failure").isZero();
            assertThat(count(runtime + ".latency.millis"))
                    .as(runtime + " latency samples")
                    .isZero();
        }
    }

    @Test
    void theLatencyHistogramExposesDropwizardsPercentileAttributesOverJmx() {
        run(COUNT_NODES);

        for (String attribute : new String[] {
            "Min",
            "Max",
            "Mean",
            "StdDev",
            "50thPercentile",
            "75thPercentile",
            "95thPercentile",
            "98thPercentile",
            "99thPercentile",
            "999thPercentile"
        }) {
            Object value = JmxQueries.attribute(system, objectName("neo4j", "latency.millis"), attribute);
            assertThat(value).as(attribute + " of latency.millis").isInstanceOf(Number.class);
            assertThat(((Number) value).doubleValue()).as(attribute).isGreaterThanOrEqualTo(0);
        }
    }

    private static void run(String cypher) {
        try (Transaction tx = db.beginTx()) {
            tx.execute(cypher).resultAsString();
            tx.commit();
        }
    }

    private static Driver driver() {
        return GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
    }

    private static long count(String metric) {
        return count("neo4j", metric);
    }

    private static long count(String database, String metric) {
        Object count = JmxQueries.attribute(system, objectName(database, metric), "Count");
        assertThat(count)
                .as("Count of " + database + " db.query.execution." + metric)
                .isInstanceOf(Long.class);
        return (Long) count;
    }

    private static String objectName(String database, String metric) {
        return "neo4j.metrics:name=neo4j.database." + database + ".db.query.execution." + metric;
    }

    private static void executeOnSystem(String cypher) {
        try (Transaction tx = system.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    private static void awaitStatus(String database, Optional<String> status) {
        Await.until(database + " has status " + status.orElse("<absent>"), () -> {
            try (Transaction tx = system.beginTx()) {
                return tx
                        .execute(
                                "SHOW DATABASES YIELD name, currentStatus WHERE name = $name RETURN currentStatus",
                                Map.of("name", database))
                        .stream()
                        .map(row -> (String) row.get("currentStatus"))
                        .findFirst()
                        .equals(status);
            }
        });
    }
}
