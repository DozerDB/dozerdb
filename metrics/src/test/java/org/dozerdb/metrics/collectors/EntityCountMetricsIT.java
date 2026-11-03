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

import java.util.ArrayList;
import java.util.List;
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
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The database data count metrics and the deprecated database data metrics (Operations Manual 5, "Database
 * data count metrics" and "Database data metrics") as an administrator observes them:
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.neo4j.count.<metric>")} and
 * {@code ...neo4j.database.neo4j.ids_in_use.<metric>}. The count names keep the manual's literal {@code neo4j.count}
 * segment. The 5 default {@code server.metrics.filter} admits the {@code ids_in_use} rows ({@code *ids_in_use*}) but
 * not the counts (it has no {@code *.neo4j.count.*} entry; the essential metrics page marks them "Not enabled by
 * default"), so the shared server runs with the default filter plus {@code *.neo4j.count.*}, the entry an
 * administrator adds to enable them; one test checks the default filter alone on a server of its own. Each test
 * provokes the real change on the default database and reads the gauges through the system database.
 */
class EntityCountMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithTheDefaultFilterAndTheCounts() {
        List<GlobbingPattern> filter = new ArrayList<>(MetricsSettings.metrics_filter.defaultValue());
        filter.add(GlobbingPattern.create("*.neo4j.count.*").get(0));
        neo4j = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.metrics_filter, filter));
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
    void creatingNodesRaisesTheNodeCountByTheirNumber() {
        long nodes = gauge("neo4j.count.node");

        try (Transaction tx = db.beginTx()) {
            for (int node = 0; node < 7; node++) {
                tx.createNode();
            }
            tx.commit();
        }

        assertThat(gauge("neo4j.count.node")).as("neo4j.count.node").isEqualTo(nodes + 7);
    }

    /**
     * Relationships of three new types between fresh nodes: the relationship count rises by their number, the
     * relationship type count by the number of new types (a type id is generated once per new type name).
     */
    @Test
    void creatingRelationshipsOfNewTypesRaisesTheRelationshipAndRelationshipTypeCounts() {
        long relationships = gauge("neo4j.count.relationship");
        long types = gauge("neo4j.count.relationship_types");

        try (Transaction tx = db.beginTx()) {
            Node from = tx.createNode();
            for (String type : new String[] {"COUNTED_A", "COUNTED_B", "COUNTED_C"}) {
                for (int relationship = 0; relationship < 2; relationship++) {
                    from.createRelationshipTo(tx.createNode(), RelationshipType.withName(type));
                }
            }
            tx.commit();
        }

        assertThat(gauge("neo4j.count.relationship"))
                .as("neo4j.count.relationship")
                .isEqualTo(relationships + 6);
        assertThat(gauge("neo4j.count.relationship_types"))
                .as("neo4j.count.relationship_types")
                .isEqualTo(types + 3);
    }

    /**
     * The deprecated ids-in-use gauges count the ids the store has generated: a fresh node, relationship or
     * relationship type takes a new id each, and every node with one property takes at least one property record.
     * Nothing in this class deletes, so no id is reused.
     */
    @Test
    void creatingEntitiesRaisesTheDeprecatedIdsInUseGauges() {
        long nodeIds = gauge("ids_in_use.node");
        long relationshipIds = gauge("ids_in_use.relationship");
        long typeIds = gauge("ids_in_use.relationship_type");
        long propertyIds = gauge("ids_in_use.property");

        try (Transaction tx = db.beginTx()) {
            Node from = tx.createNode();
            from.setProperty("in_use", 1);
            for (int relationship = 0; relationship < 4; relationship++) {
                Node to = tx.createNode();
                to.setProperty("in_use", relationship);
                from.createRelationshipTo(to, RelationshipType.withName("IDS_IN_USE_" + (relationship % 2)));
            }
            tx.commit();
        }

        assertThat(gauge("ids_in_use.node")).as("ids_in_use.node").isEqualTo(nodeIds + 5);
        assertThat(gauge("ids_in_use.relationship"))
                .as("ids_in_use.relationship")
                .isEqualTo(relationshipIds + 4);
        assertThat(gauge("ids_in_use.relationship_type"))
                .as("ids_in_use.relationship_type")
                .isEqualTo(typeIds + 2);
        assertThat(gauge("ids_in_use.property")).as("ids_in_use.property").isGreaterThanOrEqualTo(propertyIds + 5);
    }

    /**
     * With {@code *.neo4j.count.*} added to the default filter, these ten rows are registered for every database:
     * that entry matches the literal {@code neo4j.count} segment, {@code *ids_in_use*} and {@code *store.size*} the
     * rest; nothing undocumented ({@code store.size.full}, a later manual's name) is registered.
     */
    @Test
    void theCountIdsInUseAndStoreSizeGaugesOfEveryDatabaseAreRegistered() {
        for (String database : new String[] {"neo4j", "system"}) {
            String names = "neo4j.metrics:name=neo4j.database." + database + ".";
            assertThat(JmxQueries.names(system, names + "neo4j.count.*"))
                    .as("count metrics of " + database)
                    .containsExactly(
                            names + "neo4j.count.node",
                            names + "neo4j.count.relationship",
                            names + "neo4j.count.relationship_types");
            assertThat(JmxQueries.names(system, names + "ids_in_use.*"))
                    .as("ids_in_use metrics of " + database)
                    .containsExactly(
                            names + "ids_in_use.node",
                            names + "ids_in_use.property",
                            names + "ids_in_use.relationship",
                            names + "ids_in_use.relationship_type");
            assertThat(JmxQueries.names(system, names + "store.size.*"))
                    .as("store size metrics of " + database)
                    .containsExactly(
                            names + "store.size.available_reserved",
                            names + "store.size.database",
                            names + "store.size.total");
        }
    }

    /**
     * The 5 default filter alone admits the {@code ids_in_use} and store size gauges but none of the counts. The
     * server runs beside the shared one in the same JVM, so it has its own metric prefix.
     */
    @Test
    void theDefaultFilterOfThe5ReferenceDoesNotAdmitTheCounts() {
        try (Neo4j defaults =
                MetricsServers.start(builder -> builder.withConfig(MetricsSettings.metrics_prefix, "defaultfilter"))) {
            GraphDatabaseService defaultsSystem =
                    defaults.databaseManagementService().database("system");
            String names = "defaultfilter.metrics:name=defaultfilter.database.neo4j.";
            assertThat(JmxQueries.names(defaultsSystem, names + "neo4j.count.*"))
                    .isEmpty();
            assertThat(JmxQueries.names(defaultsSystem, names + "ids_in_use.*")).hasSize(4);
            assertThat(JmxQueries.names(defaultsSystem, names + "store.size.*")).hasSize(3);
        }
    }

    /**
     * Counts and store sizes follow the database (Operations Manual 5: "Database metrics are only available
     * during the lifetime of the database"): a created database brings its own, counting its own nodes, and a
     * dropped one takes them away. DozerDB does not accept the {@code WAIT} clause, so the test polls
     * {@code SHOW DATABASES}.
     */
    @Test
    void aCreatedDatabaseHasItsOwnCountAndStoreSizeMetricsUntilItIsDropped() {
        String database = "counted";
        String names = "neo4j.metrics:name=neo4j.database." + database + ".";
        execute("CREATE DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        Object nodes = JmxQueries.value(system, names + "neo4j.count.node");
        assertThat(nodes).as("neo4j.count.node of the created database").isEqualTo(0L);
        assertThat(JmxQueries.value(system, names + "store.size.database"))
                .as("store.size.database of the created database")
                .isInstanceOf(Long.class);

        try (Transaction tx =
                neo4j.databaseManagementService().database(database).beginTx()) {
            tx.createNode();
            tx.commit();
        }
        assertThat(JmxQueries.value(system, names + "neo4j.count.node"))
                .as("the created database counts its own node")
                .isEqualTo(1L);

        execute("DROP DATABASE " + database);
        awaitStatus(database, Optional.empty());
        assertThat(JmxQueries.names(system, names + "*"))
                .as("metrics of the dropped database")
                .isEmpty();
    }

    private static void execute(String cypher) {
        try (Transaction tx = system.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
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

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of " + metric).isInstanceOf(Long.class);
        return (Long) value;
    }
}
