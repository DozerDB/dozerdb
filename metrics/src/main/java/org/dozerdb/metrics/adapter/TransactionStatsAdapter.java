/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import java.util.function.LongConsumer;
import org.neo4j.kernel.impl.monitoring.TransactionSizeMonitor;
import org.neo4j.kernel.impl.transaction.stats.TransactionCounters;
import org.neo4j.storageengine.api.TransactionIdStore;

/**
 * Adapts Neo4j's per-database {@link TransactionCounters} (implemented by {@code DatabaseTransactionStats}, one
 * instance per database) and {@link TransactionIdStore} (implemented by the database's {@code LogMetadataProvider}),
 * both satisfied in the database's dependencies, to {@link TransactionStats}. The transaction size callback is the
 * {@link TransactionSizeMonitor} slot of the counters, a single volatile field that this adapter owns while a
 * transaction collector is registered.
 */
final class TransactionStatsAdapter implements TransactionStats {

    private final TransactionCounters counters;
    private final TransactionIdStore transactionIds;

    TransactionStatsAdapter(TransactionCounters counters, TransactionIdStore transactionIds) {
        this.counters = counters;
        this.transactionIds = transactionIds;
    }

    @Override
    public long started() {
        return counters.getNumberOfStartedTransactions();
    }

    @Override
    public long peakConcurrent() {
        return counters.getPeakConcurrentNumberOfTransactions();
    }

    @Override
    public long active() {
        return counters.getNumberOfActiveTransactions();
    }

    @Override
    public long activeRead() {
        return counters.getNumberOfActiveReadTransactions();
    }

    @Override
    public long activeWrite() {
        return counters.getNumberOfActiveWriteTransactions();
    }

    @Override
    public long committed() {
        return counters.getNumberOfCommittedTransactions();
    }

    @Override
    public long committedRead() {
        return counters.getNumberOfCommittedReadTransactions();
    }

    @Override
    public long committedWrite() {
        return counters.getNumberOfCommittedWriteTransactions();
    }

    @Override
    public long rolledBack() {
        return counters.getNumberOfRolledBackTransactions();
    }

    @Override
    public long rolledBackRead() {
        return counters.getNumberOfRolledBackReadTransactions();
    }

    @Override
    public long rolledBackWrite() {
        return counters.getNumberOfRolledBackWriteTransactions();
    }

    @Override
    public long rolledBackDeadlocked() {
        return counters.getNumberOfRolledBackDeadlockedTransactions();
    }

    @Override
    public long terminated() {
        return counters.getNumberOfTerminatedTransactions();
    }

    @Override
    public long terminatedRead() {
        return counters.getNumberOfTerminatedReadTransactions();
    }

    @Override
    public long terminatedWrite() {
        return counters.getNumberOfTerminatedWriteTransactions();
    }

    @Override
    public long lastCommittedTransactionId() {
        return transactionIds.getLastCommittedTransactionId();
    }

    @Override
    public long lastClosedTransactionId() {
        return transactionIds.getHighestGapFreeClosedTransactionId();
    }

    @Override
    public long lowestAvailableCommittedTransactionId() {
        return transactionIds.getLowestAvailableCommittedTransactionId();
    }

    @Override
    public void observeTransactionSizes(LongConsumer heapBytes, LongConsumer nativeBytes) {
        counters.setTransactionSizeCallback(new TransactionSizeMonitor() {
            @Override
            public void addHeapTransactionSize(long transactionSizeHeap) {
                heapBytes.accept(transactionSizeHeap);
            }

            @Override
            public void addNativeTransactionSize(long transactionSizeNative) {
                nativeBytes.accept(transactionSizeNative);
            }
        });
    }

    @Override
    public void stopObservingTransactionSizes() {
        counters.setTransactionSizeCallback(null);
    }
}
