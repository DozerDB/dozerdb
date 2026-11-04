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

import java.util.function.LongConsumer;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.TransactionStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database transaction metrics (Operations Manual 2026.10, "Database transaction metrics"): the
 * {@code transaction.*} rows of one database. The counts are Neo4j's own monotonic counters
 * ({@code DatabaseTransactionStats}) and the transaction ids those of the database's {@code TransactionIdStore},
 * exposed as counters read at report time; the active counts are gauges over the same object. The two transaction
 * size rows are histograms fed by Neo4j's transaction size callback, which this collector holds while registered.
 * One instance per started database, built from that database's dependencies.
 */
public final class TransactionCollector implements Collector {

    public static final String GROUP = "transaction";

    private final DatabaseDependencies database;

    public TransactionCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        TransactionStats stats = database.transactionStats();
        metrics.counter("transaction.started", stats::started);
        metrics.counter("transaction.peak_concurrent", stats::peakConcurrent);
        metrics.gauge("transaction.active", stats::active);
        metrics.gauge("transaction.active_read", stats::activeRead);
        metrics.gauge("transaction.active_write", stats::activeWrite);
        metrics.counter("transaction.committed", stats::committed);
        metrics.counter("transaction.committed_read", stats::committedRead);
        metrics.counter("transaction.committed_write", stats::committedWrite);
        metrics.counter("transaction.rollbacks", stats::rolledBack);
        metrics.counter("transaction.rollbacks_read", stats::rolledBackRead);
        metrics.counter("transaction.rollbacks_write", stats::rolledBackWrite);
        metrics.counter("transaction.rollbacks_deadlocked", stats::rolledBackDeadlocked);
        metrics.counter("transaction.terminated", stats::terminated);
        metrics.counter("transaction.terminated_read", stats::terminatedRead);
        metrics.counter("transaction.terminated_write", stats::terminatedWrite);
        metrics.counter("transaction.last_committed_tx_id", stats::lastCommittedTransactionId);
        metrics.counter("transaction.last_closed_tx_id", stats::lastClosedTransactionId);
        metrics.counter("transaction.lowest_available_committed_tx_id", stats::lowestAvailableCommittedTransactionId);
        LongConsumer heapSizes = metrics.histogram("transaction.tx_size_heap");
        LongConsumer nativeSizes = metrics.histogram("transaction.tx_size_native");
        stats.observeTransactionSizes(heapSizes, nativeSizes);
        metrics.onRetire(stats::stopObservingTransactionSizes);
    }
}
