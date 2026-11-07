/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;
import org.dozerdb.querylog.extension.QueryLogConfiguration;
import org.dozerdb.querylog.extension.TransactionEvents;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.query.TransactionExecutionMonitor;

/**
 * The transaction listener: Neo4j's {@link TransactionExecutionMonitor} on the global bus, which hears the start,
 * commit and rollback of every kernel transaction of every database, the system database's start-up transactions
 * included (hence registration in {@code init()}), and forwards them to the extension. Neo4j fires {@code start} from
 * the kernel's one transaction factory, {@code commit} after a successful commit and {@code rollback} after a
 * rollback, with the throwable of the failure that caused it when there was one (a transaction event listener that
 * refused the commit, for instance) and {@code null} for a rollback the client asked for; that throwable is what
 * makes the rollback a failure for the policy, so it is written whatever the threshold.
 *
 * <p>Each event first reads the configuration in force and returns when the transaction level is {@code OFF}, Neo4j's
 * default, whatever the query level, before anything is allocated. The open time handed over is measured here from the kernel transaction's own start clock
 * ({@code startTimeNanos()}, Neo4j's monotonic clock) to now, in whole milliseconds, because the kernel exposes no
 * end time for a rollback.
 */
final class TransactionExecutionListener implements TransactionExecutionMonitor {

    private final TransactionEvents events;

    TransactionExecutionListener(TransactionEvents events) {
        this.events = events;
    }

    @Override
    public void start(KernelTransaction tx) {
        QueryLogConfiguration configuration = unlessTransactionsOff();
        if (configuration == null) {
            return;
        }
        events.transactionStarted(
                configuration, tx.getTransactionSequenceNumber(), () -> TransactionEntries.started(tx));
    }

    @Override
    public void commit(KernelTransaction tx) {
        QueryLogConfiguration configuration = unlessTransactionsOff();
        if (configuration == null) {
            return;
        }
        long openMillis = openMillis(tx);
        events.transactionEnded(
                configuration,
                tx.getTransactionSequenceNumber(),
                Outcome.SUCCESS,
                openMillis,
                () -> TransactionEntries.committed(tx, openMillis));
    }

    @Override
    public void rollback(KernelTransaction tx, Throwable failure) {
        QueryLogConfiguration configuration = unlessTransactionsOff();
        if (configuration == null) {
            return;
        }
        long openMillis = openMillis(tx);
        events.transactionEnded(
                configuration,
                tx.getTransactionSequenceNumber(),
                failure == null ? Outcome.SUCCESS : Outcome.FAILURE,
                openMillis,
                () -> TransactionEntries.rolledBack(tx, openMillis, failure));
    }

    /**
     * The configuration in force, or {@code null} when the transaction level is {@code OFF}: the one volatile read
     * every event begins with, and nothing allocated on the way out.
     */
    private QueryLogConfiguration unlessTransactionsOff() {
        QueryLogConfiguration configuration = events.configuration();
        return configuration.transactionsOff() ? null : configuration;
    }

    /** How long the transaction has been open, from Neo4j's start clock to now; never negative. */
    private static long openMillis(KernelTransaction tx) {
        return Math.max(0, NANOSECONDS.toMillis(System.nanoTime() - tx.startTimeNanos()));
    }
}
