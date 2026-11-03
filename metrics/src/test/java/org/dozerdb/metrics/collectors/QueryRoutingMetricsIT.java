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
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The query routing metrics (Operations Manual 5, "Query routing metrics") as an administrator observes them:
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.routing.query.count.<location>")}. Neo4j's query router
 * decides where every query sent over Bolt runs; on a single DozerDB instance that is always the local server. The
 * metrics are read through an embedded transaction, which the router never sees.
 */
class QueryRoutingMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.dbms.routing.query.count.";
    private static final String COUNT_NODES = "MATCH (n) RETURN count(n) AS nodes";

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
    void everyQueryOfASessionBoundToADatabaseNameIsRoutedLocallyOnce() {
        long local = count("local");

        try (Driver driver = driver();
                Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            for (int i = 0; i < 3; i++) {
                session.run(COUNT_NODES).consume();
            }
            try (org.neo4j.driver.Transaction tx = session.beginTransaction()) {
                tx.run(COUNT_NODES).consume();
                tx.run(COUNT_NODES).consume();
                tx.commit();
            }
        }

        Await.until("five queries are routed", () -> count("local") >= local + 5);
        assertThat(count("local")).as("local").isEqualTo(local + 5);
    }

    /** A session without a database name runs on the default (home) database, through the same router. */
    @Test
    void everyQueryOfASessionOnTheDefaultDatabaseIsRoutedLocallyOnce() {
        long local = count("local");

        try (Driver driver = driver();
                Session session = driver.session()) {
            for (int i = 0; i < 3; i++) {
                session.run(COUNT_NODES).consume();
            }
        }

        Await.until("three queries are routed", () -> count("local") >= local + 3);
        assertThat(count("local")).as("local").isEqualTo(local + 3);
    }

    /**
     * An embedded transaction executes its queries on the database's Cypher engine directly
     * ({@code TransactionImpl.execute}); the query router is not involved, so nothing is routed.
     */
    @Test
    void anEmbeddedQueryIsNotRouted() {
        long local = count("local");

        for (int i = 0; i < 3; i++) {
            try (Transaction tx = db.beginTx()) {
                tx.execute(COUNT_NODES).resultAsString();
                tx.commit();
            }
        }

        assertThat(count("local")).as("local").isEqualTo(local);
    }

    /**
     * A single instance has no other cluster member and no remote database alias to route a query to: DozerDB does
     * not implement {@code CREATE ALIAS}, an Enterprise command, so no query can be routed outside the server. If a
     * later DozerDB accepts remote aliases, this test fails and the {@code remote_external} row is re-tiered.
     */
    @Test
    void theRemoteCountsStayAtZeroOnASingleInstanceWithoutRemoteAliases() {
        GraphDatabaseService system = neo4j.databaseManagementService().database("system");
        assertThatThrownBy(() -> {
                    try (Transaction tx = system.beginTx()) {
                        tx.execute("CREATE ALIAS elsewhere FOR DATABASE neo4j AT 'neo4j://localhost:7687' "
                                        + "USER somebody PASSWORD 'secret'")
                                .close();
                        tx.commit();
                    }
                })
                .as("a remote database alias")
                .isInstanceOf(QueryExecutionException.class)
                .hasMessageStartingWith("Unsupported administration command: CREATE ALIAS");

        try (Driver driver = driver();
                Session session = driver.session()) {
            session.run(COUNT_NODES).consume();
        }
        try (Driver driver = driver();
                Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.run("SHOW DATABASES").consume();
        }

        assertThat(count("remote_internal")).as("remote_internal").isZero();
        assertThat(count("remote_external")).as("remote_external").isZero();
    }

    private static Driver driver() {
        return GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
    }

    private static long count(String location) {
        Object count = JmxQueries.attribute(db, OBJECT_NAME_PREFIX + location, "Count");
        assertThat(count).as("Count of routing.query.count." + location).isInstanceOf(Long.class);
        return (Long) count;
    }
}
