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

import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The database transaction metrics (Operations Manual 5, "Database transaction metrics") as an administrator
 * observes them: {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.transaction.<metric>")}.
 * Each test provokes the real event on the default database and reads the metrics through the system database,
 * so the reading transaction is never among the transactions observed.
 */
class TransactionMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.transaction.";

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerWithAnOpenFilter() {
        neo4j = MetricsServers.start(
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

    @Test
    void aCommittedWriteTransactionIsCountedAsStartedCommittedAndCommittedWrite() {
        long started = count("started");
        long committed = count("committed");
        long committedWrite = count("committed_write");
        long committedRead = count("committed_read");

        try (Transaction tx = db.beginTx()) {
            tx.createNode();
            tx.commit();
        }

        assertThat(count("started")).as("started").isEqualTo(started + 1);
        assertThat(count("committed")).as("committed").isEqualTo(committed + 1);
        assertThat(count("committed_write")).as("committed_write").isEqualTo(committedWrite + 1);
        assertThat(count("committed_read")).as("committed_read").isEqualTo(committedRead);
    }

    @Test
    void aRolledBackWriteTransactionIsCountedAsRollbackAndRollbackWrite() {
        long rollbacks = count("rollbacks");
        long rollbacksWrite = count("rollbacks_write");
        long rollbacksRead = count("rollbacks_read");

        try (Transaction tx = db.beginTx()) {
            tx.createNode();
            tx.rollback();
        }

        assertThat(count("rollbacks")).as("rollbacks").isEqualTo(rollbacks + 1);
        assertThat(count("rollbacks_write")).as("rollbacks_write").isEqualTo(rollbacksWrite + 1);
        assertThat(count("rollbacks_read")).as("rollbacks_read").isEqualTo(rollbacksRead);
    }

    @Test
    void aRolledBackReadTransactionIsCountedAsRollbackRead() {
        long rollbacksRead = count("rollbacks_read");

        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (n) RETURN count(n) AS nodes").close();
            tx.rollback();
        }

        assertThat(count("rollbacks_read")).as("rollbacks_read").isEqualTo(rollbacksRead + 1);
    }

    @Test
    void aTerminatedWriteTransactionIsCountedAsTerminatedAndTerminatedWrite() {
        long terminated = count("terminated");
        long terminatedWrite = count("terminated_write");
        long terminatedRead = count("terminated_read");

        try (Transaction tx = db.beginTx()) {
            tx.createNode();
            tx.terminate();
        }

        assertThat(count("terminated")).as("terminated").isEqualTo(terminated + 1);
        assertThat(count("terminated_write")).as("terminated_write").isEqualTo(terminatedWrite + 1);
        assertThat(count("terminated_read")).as("terminated_read").isEqualTo(terminatedRead);
    }

    @Test
    void aTerminatedReadTransactionIsCountedAsTerminatedRead() {
        long terminatedRead = count("terminated_read");

        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (n) RETURN count(n) AS nodes").close();
            tx.terminate();
        }

        assertThat(count("terminated_read")).as("terminated_read").isEqualTo(terminatedRead + 1);
    }

    /**
     * The Operations Manual 5 lists neither {@code transaction.rollbacks_deadlocked} nor
     * {@code transaction.lowest_available_committed_tx_id} (both arrived in later Neo4j releases, and Neo4j 5.26.32 has
     * no counter for either), so this line does not register them, even under an open filter.
     */
    @Test
    void theRowsTheManualDoesNotDocumentAreNotRegistered() {
        assertThat(JmxQueries.names(system, OBJECT_NAME_PREFIX + "rollbacks_deadlocked"))
                .isEmpty();
        assertThat(JmxQueries.names(system, OBJECT_NAME_PREFIX + "lowest_available_committed_tx_id"))
                .isEmpty();
    }

    /**
     * Transactions open at the same time: a transaction is a read transaction until it writes, so two transactions
     * that only read and one that has created a node are two active read and one active write transaction, and
     * the peak is at least their number.
     */
    @Test
    void openTransactionsAreActiveByReadAndWriteAndRaiseThePeak() {
        long active = gauge("active");
        long activeRead = gauge("active_read");
        long activeWrite = gauge("active_write");

        try (Transaction firstReader = db.beginTx();
                Transaction secondReader = db.beginTx();
                Transaction writer = db.beginTx()) {
            firstReader.execute("MATCH (n) RETURN count(n) AS nodes").close();
            secondReader.execute("MATCH (n) RETURN count(n) AS nodes").close();
            writer.createNode();

            assertThat(gauge("active")).as("active").isEqualTo(active + 3);
            assertThat(gauge("active_read")).as("active_read").isEqualTo(activeRead + 2);
            assertThat(gauge("active_write")).as("active_write").isEqualTo(activeWrite + 1);
            assertThat(count("peak_concurrent")).as("peak_concurrent").isGreaterThanOrEqualTo(3);
        }

        assertThat(gauge("active")).as("active after closing").isEqualTo(active);
        assertThat(gauge("active_read")).as("active_read after closing").isEqualTo(activeRead);
        assertThat(gauge("active_write")).as("active_write after closing").isEqualTo(activeWrite);
    }

    @Test
    void theLastCommittedTransactionIdRisesByOnePerCommittedWriteTransactionAndTheLastClosedFollows() {
        long lastCommitted = count("last_committed_tx_id");

        try (Transaction tx = db.beginTx()) {
            tx.createNode();
            tx.commit();
        }
        assertThat(count("last_committed_tx_id")).as("after a write").isEqualTo(lastCommitted + 1);
        assertThat(count("last_closed_tx_id")).as("closed follows committed").isEqualTo(lastCommitted + 1);

        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (n) RETURN count(n) AS nodes").close();
            tx.commit();
        }
        assertThat(count("last_committed_tx_id"))
                .as("a read commit takes no id")
                .isEqualTo(lastCommitted + 1);
    }

    /**
     * Neo4j reports every transaction's heap and native size when it closes; the two size rows are histograms over
     * those sizes, with the percentile attributes the fleet-management module reads. The property key is created
     * first, because a new token is committed in an internal transaction of its own, which has a size too.
     */
    @Test
    void committedTransactionsFeedTheSizeHistograms() {
        try (Transaction tx = db.beginTx()) {
            tx.createNode().setProperty("payload", "p");
            tx.commit();
        }
        long heapSizes = count("tx_size_heap");
        long nativeSizes = count("tx_size_native");

        try (Transaction tx = db.beginTx()) {
            for (int node = 0; node < 100; node++) {
                tx.createNode().setProperty("payload", "p".repeat(1024));
            }
            tx.commit();
        }

        assertThat(count("tx_size_heap")).as("tx_size_heap count").isEqualTo(heapSizes + 1);
        assertThat(count("tx_size_native")).as("tx_size_native count").isEqualTo(nativeSizes + 1);
        assertThat((Long) JmxQueries.attribute(system, OBJECT_NAME_PREFIX + "tx_size_heap", "Max"))
                .as("the heap size of a transaction creating 100 nodes")
                .isGreaterThan(100 * 1024L);
        assertThat(JmxQueries.attributeNames(system, OBJECT_NAME_PREFIX + "tx_size_heap")
                        .get(OBJECT_NAME_PREFIX + "tx_size_heap"))
                .contains("50thPercentile", "75thPercentile", "99thPercentile", "Min", "Mean", "StdDev");
    }

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of transaction." + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} attribute of a counter of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        Object count = JmxQueries.attribute(system, OBJECT_NAME_PREFIX + metric, "Count");
        assertThat(count).as("Count of transaction." + metric).isInstanceOf(Long.class);
        return (Long) count;
    }
}
