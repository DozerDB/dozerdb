/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import static org.assertj.core.api.Assertions.assertThat;

import org.dozerdb.metrics.JmxQueries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;

/**
 * The first metric end to end: with default settings, an administrator can run
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.vm.heap.used")} and read a value, exactly the
 * object name cypher-shell {@code :sysinfo} and the fleet-management module build for a global metric.
 */
class JmxHeapMetricsIT {

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithDefaultMetricsSettings() {
        neo4j = DozerDbBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .build();
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void heapUsedIsReadableThroughQueryJmxUnderTheDocumentedObjectName() {
        Object value = JmxQueries.value(db, "neo4j.metrics:name=neo4j.dbms.vm.heap.used");

        assertThat(value).isInstanceOf(Long.class);
        assertThat((Long) value).isPositive();
    }

    @Test
    void heapGaugesAreGlobalMetricsInTheDbmsScope() {
        assertThat(JmxQueries.names(db, "neo4j.metrics:name=*vm.heap*"))
                .allMatch(name -> name.startsWith("neo4j.metrics:name=neo4j.dbms.vm.heap."))
                .isNotEmpty();
    }
}
