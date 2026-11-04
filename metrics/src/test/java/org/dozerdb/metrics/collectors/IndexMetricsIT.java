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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.ExecutionPlanDescription;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The database index metrics (Operations Manual 2026.10, "Database index metrics") as an administrator observes
 * them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.index.<type>.<metric>")}. Each test
 * creates an index of one type on its own label, populates it, confirms with {@code EXPLAIN} that a query is
 * planned through it, runs the query and watches the type's {@code populated} and {@code queried} counters rise,
 * then drops the index and sees both counts kept.
 * Neo4j counts a population job when the index goes online, and reports index reads to the per-type counters on
 * its usage report job ({@code internal.dbms.index.usage_report_frequency}, 10 s by default, 1 s here), so the
 * {@code queried} assertions poll.
 */
class IndexMetricsIT {

    /** The {@code <type>} segments of the documented metric pairs, in the manual's order. */
    private static final List<String> DOCUMENTED_TYPES =
            List.of("fulltext", "lookup", "text", "range", "point", "vector");

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerReportingIndexUsageEverySecond() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(GraphDatabaseInternalSettings.index_usage_report_frequency, Duration.ofSeconds(1)));
        db = neo4j.defaultDatabaseService();
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aRangeIndexIsCountedWhenPopulatedAndWhenAnEqualitySeekQueriesIt() {
        long populated = count("range", "populated");
        long queried = count("range", "queried");

        execute(db, "UNWIND range(1, 20) AS i CREATE (:Person {name: 'person-' + i})");
        execute(db, "CREATE INDEX person_name FOR (n:Person) ON (n.name)");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("range", "populated")).as("range.populated").isEqualTo(populated + 1);

        String query = "MATCH (n:Person) WHERE n.name = 'person-7' RETURN count(n) AS matches";
        assertThat(planOf(query)).as("the plan seeks the range index").contains("NodeIndexSeek", "RANGE INDEX");
        assertThat(single(query, "matches")).isEqualTo(1L);
        Await.until("range.queried rises after the seek", () -> count("range", "queried") > queried);

        dropKeepsTheCounts("range", "person_name");
        assertThat(planOf(query)).as("the plan without the index").doesNotContain("RANGE INDEX");
        assertThat(single(query, "matches")).as("the query still answers").isEqualTo(1L);
        execute(db, "CREATE INDEX person_name FOR (n:Person) ON (n.name)");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("range", "populated"))
                .as("range.populated counts the second population job")
                .isEqualTo(populated + 2);
        dropKeepsTheCounts("range", "person_name");
    }

    @Test
    void aTextIndexIsCountedWhenPopulatedAndWhenAContainsScanQueriesIt() {
        long populated = count("text", "populated");
        long queried = count("text", "queried");

        execute(db, "UNWIND range(1, 20) AS i CREATE (:Book {title: 'book number ' + i})");
        execute(db, "CREATE TEXT INDEX book_title FOR (n:Book) ON (n.title)");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("text", "populated")).as("text.populated").isEqualTo(populated + 1);

        String query = "MATCH (n:Book) WHERE n.title CONTAINS 'number 1' RETURN count(n) AS matches";
        assertThat(planOf(query)).as("the plan scans the text index").contains("NodeIndexContainsScan", "TEXT INDEX");
        assertThat(single(query, "matches")).isEqualTo(11L);
        Await.until("text.queried rises after the contains scan", () -> count("text", "queried") > queried);
        dropKeepsTheCounts("text", "book_title");
    }

    @Test
    void aPointIndexIsCountedWhenPopulatedAndWhenADistanceQueryUsesIt() {
        long populated = count("point", "populated");
        long queried = count("point", "queried");

        execute(db, "UNWIND range(1, 20) AS i CREATE (:Place {location: point({x: i, y: i})})");
        execute(db, "CREATE POINT INDEX place_location FOR (n:Place) ON (n.location)");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("point", "populated")).as("point.populated").isEqualTo(populated + 1);

        String query = "MATCH (n:Place) WHERE point.distance(n.location, point({x: 0, y: 0})) < 3 "
                + "RETURN count(n) AS matches";
        assertThat(planOf(query)).as("the plan seeks the point index").contains("NodeIndexSeek", "POINT INDEX");
        assertThat(single(query, "matches")).isEqualTo(2L);
        Await.until("point.queried rises after the distance query", () -> count("point", "queried") > queried);
        dropKeepsTheCounts("point", "place_location");
    }

    @Test
    void aFulltextIndexIsCountedWhenPopulatedAndWhenTheFulltextProcedureQueriesIt() {
        long populated = count("fulltext", "populated");
        long queried = count("fulltext", "queried");

        execute(db, "UNWIND range(1, 20) AS i CREATE (:Article {body: 'graph databases, article ' + i})");
        execute(db, "CREATE FULLTEXT INDEX article_body FOR (n:Article) ON EACH [n.body]");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("fulltext", "populated")).as("fulltext.populated").isEqualTo(populated + 1);

        String query = "CALL db.index.fulltext.queryNodes('article_body', 'graph') YIELD node "
                + "RETURN count(node) AS matches";
        assertThat(single(query, "matches")).isEqualTo(20L);
        Await.until("fulltext.queried rises after the fulltext query", () -> count("fulltext", "queried") > queried);
        dropKeepsTheCounts("fulltext", "article_body");
    }

    /** Community creates vector indexes; the query goes through the vector procedure, as the manual documents it. */
    @Test
    void aVectorIndexIsCountedWhenPopulatedAndWhenTheVectorProcedureQueriesIt() {
        long populated = count("vector", "populated");
        long queried = count("vector", "queried");

        execute(db, "UNWIND range(1, 20) AS i CREATE (:Document {embedding: [toFloat(i), 1.0, 0.0]})");
        execute(
                db,
                "CREATE VECTOR INDEX document_embedding FOR (n:Document) ON (n.embedding) "
                        + "OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.similarity_function`: 'cosine'}}");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("vector", "populated")).as("vector.populated").isEqualTo(populated + 1);

        String query = "CALL db.index.vector.queryNodes('document_embedding', 3, [1.0, 1.0, 0.0]) YIELD node "
                + "RETURN count(node) AS matches";
        assertThat(single(query, "matches")).isEqualTo(3L);
        Await.until("vector.queried rises after the vector query", () -> count("vector", "queried") > queried);
        dropKeepsTheCounts("vector", "document_embedding");
    }

    /**
     * Every database has its two token lookup indexes from the start; this test drops the node one and creates it
     * again, so the population job is counted, then scans a label, which Cypher answers from that index without a
     * property predicate.
     */
    @Test
    void aLookupIndexIsCountedWhenPopulatedAndWhenALabelScanQueriesIt() {
        long populated = count("lookup", "populated");
        long queried = count("lookup", "queried");

        String nodeLookup = (String)
                single("SHOW LOOKUP INDEXES YIELD name, entityType WHERE entityType = 'NODE' RETURN name", "name");
        execute(db, "DROP INDEX " + nodeLookup);
        execute(db, "CREATE LOOKUP INDEX node_labels FOR (n) ON EACH labels(n)");
        execute(db, "CALL db.awaitIndexes()");
        assertThat(count("lookup", "populated")).as("lookup.populated").isEqualTo(populated + 1);

        execute(db, "CREATE (:City {name: 'Lund'})");
        // A count alone would be answered from the count store; returning a property forces the label scan.
        String query = "MATCH (n:City) RETURN n.name AS city";
        assertThat(planOf(query))
                .as("the plan scans the label through the lookup index")
                .contains("NodeByLabelScan");
        assertThat(single(query, "city")).isEqualTo("Lund");
        Await.until("lookup.queried rises after the label scan", () -> count("lookup", "queried") > queried);

        dropKeepsTheCounts("lookup", "node_labels");
        assertThat(single(query, "city"))
                .as("the query still answers without a node lookup index")
                .isEqualTo("Lund");
        execute(db, "CREATE LOOKUP INDEX " + nodeLookup + " FOR (n) ON EACH labels(n)");
        execute(db, "CALL db.awaitIndexes()");
    }

    /**
     * Index metrics follow the database (Operations Manual 2026.10: "Database metrics are only available during the
     * lifetime of the database"): a created database has its own twelve counters, which count its own indexes only;
     * they disappear while it is stopped and come back when it starts, and a dropped database takes them away.
     * Neo4j keeps the counters in memory per database object ({@code DatabaseIndexStats}), which DozerDB keeps across
     * {@code STOP} and {@code START}, so a restarted database reports the counts it had; a server restart resets
     * them. DozerDB does not accept the {@code WAIT} clause, so the test polls {@code SHOW DATABASES}.
     */
    @Test
    void aCreatedDatabaseHasItsOwnIndexMetricsThatFollowItsLifecycle() {
        String database = "indexed";
        String names = "neo4j.metrics:name=neo4j.database." + database + ".index.";
        execute(system, "CREATE DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        assertThat(JmxQueries.names(system, names + "*"))
                .as("index metrics of the created database")
                .containsExactlyInAnyOrder(DOCUMENTED_TYPES.stream()
                        .flatMap(type -> Stream.of(names + type + ".queried", names + type + ".populated"))
                        .toArray(String[]::new));

        long ownPopulated = count(database, "range", "populated");
        long defaultPopulated = count("range", "populated");
        GraphDatabaseService indexed = neo4j.databaseManagementService().database(database);
        execute(indexed, "UNWIND range(1, 5) AS i CREATE (:Tool {name: 'tool-' + i})");
        execute(indexed, "CREATE INDEX tool_name FOR (n:Tool) ON (n.name)");
        execute(indexed, "CALL db.awaitIndexes()");
        assertThat(count(database, "range", "populated"))
                .as("range.populated of the created database")
                .isEqualTo(ownPopulated + 1);
        assertThat(count("range", "populated"))
                .as("range.populated of the default database")
                .isEqualTo(defaultPopulated);

        execute(system, "STOP DATABASE " + database);
        awaitStatus(database, Optional.of("offline"));
        assertThat(JmxQueries.names(system, names + "*"))
                .as("index metrics of the stopped database")
                .isEmpty();

        execute(system, "START DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        assertThat(count(database, "range", "populated"))
                .as("range.populated of the restarted database keeps its count")
                .isEqualTo(ownPopulated + 1);

        execute(system, "DROP DATABASE " + database);
        awaitStatus(database, Optional.empty());
        assertThat(JmxQueries.names(system, names + "*"))
                .as("index metrics of the dropped database")
                .isEmpty();
    }

    /**
     * Dropping an index keeps both counts of its type: they count per type, not per index, and a drop is neither a
     * population nor a query. Reads of the dropped index not yet reported by the usage report job are lost with it,
     * so {@code queried} may stay where it was but never falls.
     */
    private static void dropKeepsTheCounts(String indexType, String index) {
        long populated = count(indexType, "populated");
        long queried = count(indexType, "queried");
        execute(db, "DROP INDEX " + index);
        assertThat(count(indexType, "populated"))
                .as(indexType + ".populated after the drop")
                .isEqualTo(populated);
        assertThat(count(indexType, "queried"))
                .as(indexType + ".queried after the drop")
                .isGreaterThanOrEqualTo(queried);
    }

    /** Polls {@code SHOW DATABASES} until the database reports the status, or is gone when none is expected. */
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

    private static void execute(GraphDatabaseService database, String cypher) {
        try (Transaction tx = database.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    private static Object single(String cypher, String column) {
        try (Transaction tx = db.beginTx()) {
            return tx.execute(cypher).stream()
                    .map(row -> row.get(column))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /** Every operator of the query's plan with its details, one line each, as {@code EXPLAIN} shows them. */
    private static String planOf(String cypher) {
        try (Transaction tx = db.beginTx()) {
            StringBuilder plan = new StringBuilder();
            describe(tx.execute("EXPLAIN " + cypher).getExecutionPlanDescription(), plan);
            return plan.toString();
        }
    }

    private static void describe(ExecutionPlanDescription operator, StringBuilder plan) {
        Map<String, Object> arguments = operator.getArguments();
        plan.append(operator.getName())
                .append(' ')
                .append(arguments.getOrDefault("Details", ""))
                .append('\n');
        operator.getChildren().forEach(child -> describe(child, plan));
    }

    /** The {@code Count} attribute of one index counter of the default database, read through the system database. */
    private static long count(String indexType, String metric) {
        return count("neo4j", indexType, metric);
    }

    /** The {@code Count} attribute of one index counter of a database, read through the system database. */
    private static long count(String database, String indexType, String metric) {
        String name = "index." + indexType + "." + metric;
        Object count =
                JmxQueries.attribute(system, "neo4j.metrics:name=neo4j.database." + database + "." + name, "Count");
        assertThat(count).as("Count of " + name + " of " + database).isInstanceOf(Long.class);
        return (Long) count;
    }
}
