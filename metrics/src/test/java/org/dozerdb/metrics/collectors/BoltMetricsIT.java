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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnectorInternalSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * The Bolt metrics (Operations Manual 2026.10, "Bolt metrics") as an administrator observes them:
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.bolt.<metric>")} after real Bolt traffic from the Neo4j
 * Java driver against a server whose Bolt connector listens on a free port.
 */
class BoltMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.dbms.bolt.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithBoltAndAnOpenFilter() {
        neo4j = MetricsServers.startWithBolt(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aDriverConnectionIsCountedAsOpenedWhenNegotiatedAndClosedWhenTheDriverCloses() {
        long opened = count("connections_opened");
        long closed = count("connections_closed");

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
            assertThat(count("connections_opened")).as("opened while connected").isEqualTo(opened + 1);
            assertThat(count("connections_closed")).as("closed while connected").isEqualTo(closed);
        }

        Await.until("the server counts the closed connection", () -> count("connections_closed") == closed + 1);
    }

    /**
     * The driver sends HELLO, LOGON, RUN and PULL for one auto-commit query and GOODBYE on close; every message is
     * received, started and done, and none fails. The exact number depends on the driver, so the test asserts the
     * three counts move together.
     */
    @Test
    void everyMessageOfAQueryIsReceivedStartedAndDone() {
        long received = count("messages_received");
        long started = count("messages_started");
        long done = count("messages_done");
        long failed = count("messages_failed");

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
        }

        Await.until(
                "the server has processed every message of the connection", () -> count("messages_done") > done + 3);
        long receivedMessages = count("messages_received") - received;
        assertThat(receivedMessages).as("HELLO, LOGON, RUN, PULL and GOODBYE").isGreaterThanOrEqualTo(4);
        assertThat(count("messages_started") - started).as("started").isEqualTo(receivedMessages);
        assertThat(count("messages_done") - done).as("done").isEqualTo(receivedMessages);
        assertThat(count("messages_failed")).as("failed").isEqualTo(failed);
    }

    /**
     * A query that fails is answered with a FAILURE response and its message is done, not failed: Neo4j counts a
     * message as failed only when its processing throws out of the state machine, which terminates the connection.
     */
    @Test
    void aFailingQueryIsDoneNotFailed() {
        long done = count("messages_done");
        long failed = count("messages_failed");

        try (Driver driver = driver();
                Session session = driver.session()) {
            assertThatThrownBy(() -> session.run("RETURN unknownFunction()").consume())
                    .isInstanceOf(ClientException.class);
        }

        Await.until("the failing RUN is done", () -> count("messages_done") > done + 2);
        assertThat(count("messages_failed")).as("failed").isEqualTo(failed);
    }

    /**
     * Authentication that fails throws out of the state machine and terminates the connection: that is a failed
     * message. The server runs beside the shared one in the same JVM with authentication enabled, so it needs its
     * own metric prefix.
     */
    @Test
    void aConnectionWhoseAuthenticationFailsHasAFailedMessage() {
        String prefix = "boltauth";
        String failed = prefix + ".metrics:name=" + prefix + ".dbms.bolt.messages_failed";
        try (Neo4j authenticating = MetricsServers.startWithBolt(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, prefix)
                        .withConfig(GraphDatabaseSettings.auth_enabled, true))) {
            GraphDatabaseService authenticatingDb = authenticating.defaultDatabaseService();
            long before = (Long) JmxQueries.attribute(authenticatingDb, failed, "Count");

            try (Driver driver = GraphDatabase.driver(authenticating.boltURI(), AuthTokens.basic("neo4j", "wrong"))) {
                assertThatThrownBy(driver::verifyConnectivity).isInstanceOf(AuthenticationException.class);
            }

            Await.until(
                    "the rejected LOGON is counted as failed",
                    () -> (Long) JmxQueries.attribute(authenticatingDb, failed, "Count") == before + 1);
        }
    }

    /**
     * A connection is running while a worker thread is bound to it and idle otherwise. Neo4j keeps the worker bound
     * while the connection has an open transaction, so an open explicit transaction is a running connection; once it
     * is committed the thread is released and the connection, still open, is idle; a closed connection is neither.
     */
    @Test
    void aConnectionWithAnOpenTransactionIsRunningAndOneWithoutIsIdle() {
        Await.until(
                "every earlier connection is closed", () -> count("connections_opened") == count("connections_closed"));
        assertThat(gauge("connections_running"))
                .as("running with no connection")
                .isZero();
        assertThat(gauge("connections_idle")).as("idle with no connection").isZero();

        try (Driver driver = driver();
                Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                tx.run("RETURN 1").consume();

                assertThat(gauge("connections_running"))
                        .as("running with an open transaction")
                        .isEqualTo(1);
                assertThat(gauge("connections_idle"))
                        .as("idle with an open transaction")
                        .isZero();
                tx.commit();
            }
            Await.until(
                    "the worker thread is released after the commit",
                    () -> gauge("connections_running") == 0 && gauge("connections_idle") == 1);
        }

        Await.until("the closed connection is neither running nor idle", () -> gauge("connections_idle") == 0);
        assertThat(gauge("connections_running")).as("running after closing").isZero();
    }

    /**
     * Processing time is the worker's time on a message; queue time the wait between a message's arrival and its
     * start. The driver pipelines RUN and PULL, so while the worker parses and plans a RUN the PULL waits in the
     * connection's queue: a query whose text is large enough to take milliseconds to parse moves both counters
     * under default settings, with no thread pool queue configured.
     */
    @Test
    void aSlowRunAccumulatesProcessingTimeAndMakesThePipelinedPullAccumulateQueueTime() {
        long processing = count("accumulated_processing_time");
        long queue = count("accumulated_queue_time");
        String longLiteral =
                IntStream.range(0, 20_000).mapToObj(Integer::toString).collect(Collectors.joining(",", "[", "]"));

        try (Driver driver = driver();
                Session session = driver.session()) {
            assertThat(session.run("RETURN size(" + longLiteral + ") AS n")
                            .single()
                            .get("n")
                            .asLong())
                    .isEqualTo(20_000);
        }

        Await.until(
                "the RUN's processing time is accumulated", () -> count("accumulated_processing_time") > processing);
        assertThat(count("accumulated_queue_time"))
                .as("the PULL waited while the RUN was parsed")
                .isGreaterThan(queue);
    }

    /** Every release of a worker thread adds the time it was bound to the connection to the histogram. */
    @Test
    void releasingAWorkerThreadFeedsTheBoundTimeHistogram() {
        long samples = count("worker_thread_bound_time");

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
        }

        Await.until("the worker thread releases feed the histogram", () -> count("worker_thread_bound_time") > samples);
        assertThat(JmxQueries.attributeNames(db, OBJECT_NAME_PREFIX + "worker_thread_bound_time")
                        .get(OBJECT_NAME_PREFIX + "worker_thread_bound_time"))
                .contains("50thPercentile", "75thPercentile", "99thPercentile", "Max", "Min", "Mean", "StdDev");
    }

    /**
     * Neo4j installs the listener behind the three response rows only with the internal setting
     * {@code internal.server.bolt.response_metrics=true}: a SUCCESS response, a FAILURE response and the IGNORED
     * response the pipelined PULL gets after a failed RUN are then counted. The server runs beside the shared one, so
     * it needs its own metric prefix.
     */
    @Test
    void responsesAreCountedBySuccessFailedAndIgnoredWhenTheResponseMetricsSettingIsOn() {
        String prefix = "boltresponses";
        String objectNamePrefix = prefix + ".metrics:name=" + prefix + ".dbms.bolt.";
        try (Neo4j responses = MetricsServers.startWithBolt(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(MetricsSettings.metrics_prefix, prefix)
                        .withConfig(BoltConnectorInternalSettings.enable_response_metrics, true))) {
            GraphDatabaseService responsesDb = responses.defaultDatabaseService();
            long success = (Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_success", "Count");
            long failed = (Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_failed", "Count");
            long ignored = (Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_ignored", "Count");

            try (Driver driver = GraphDatabase.driver(responses.boltURI(), AuthTokens.none());
                    Session session = driver.session()) {
                session.run("RETURN 1").consume();
                Await.until(
                        "HELLO, LOGON, RUN and PULL succeeded",
                        () -> (Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_success", "Count")
                                >= success + 4);
                assertThat((Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_failed", "Count"))
                        .as("failed after a successful query")
                        .isEqualTo(failed);

                assertThatThrownBy(() -> session.run("RETURN unknownFunction()").consume())
                        .isInstanceOf(ClientException.class);
                Await.until(
                        "the failing RUN is a failed response and its PULL an ignored one",
                        () -> (Long) JmxQueries.attribute(responsesDb, objectNamePrefix + "response_failed", "Count")
                                        == failed + 1
                                && (Long) JmxQueries.attribute(
                                                responsesDb, objectNamePrefix + "response_ignored", "Count")
                                        == ignored + 1);
            }
        }
    }

    /** Without the internal setting the response hooks never fire: the three counters stay at zero. */
    @Test
    void responsesAreNotCountedUnderDefaultSettings() {
        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
            assertThatThrownBy(() -> session.run("RETURN unknownFunction()").consume())
                    .isInstanceOf(ClientException.class);
        }

        assertThat(count("response_success")).as("response_success").isZero();
        assertThat(count("response_failed")).as("response_failed").isZero();
        assertThat(count("response_ignored")).as("response_ignored").isZero();
    }

    /** Without {@code server.bolt.telemetry.enabled} the server offers no telemetry, the driver sends none, and the four driver counters stay at zero. */
    @Test
    void driverApiCallsAreNotCountedWithoutServerTelemetry() {
        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run("RETURN 1").consume();
            session.executeRead(tx -> tx.run("RETURN 1").consume());
            driver.executableQuery("RETURN 1").execute();
        }

        for (String api : List.of(
                "managed_transaction_function_calls",
                "unmanaged_transaction_calls",
                "implicit_transaction_calls",
                "execute_calls")) {
            Object count = JmxQueries.attribute(db, "neo4j.metrics:name=neo4j.dbms.bolt_driver.api." + api, "Count");
            assertThat(count).as("bolt_driver.api." + api).isEqualTo(0L);
        }
    }

    private static Driver driver() {
        return GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
    }

    /** The {@code Value} attribute of a Bolt gauge, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(db, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of bolt." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} attribute of a Bolt counter, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(db, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of bolt." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }
}
