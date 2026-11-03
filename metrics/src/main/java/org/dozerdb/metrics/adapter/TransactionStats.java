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

/**
 * One database's transaction statistics as the transaction collector reads them: Neo4j's own monotonic counters,
 * kept per database by {@code DatabaseTransactionStats} on the database's threads, and the transaction ids of the
 * database's {@code TransactionIdStore}, all read here at report time; plus the database's one transaction size
 * callback slot, through which Neo4j reports every closing transaction's size.
 */
public interface TransactionStats {

    long started();

    /** The highest number of transactions open at the same time since the database started. */
    long peakConcurrent();

    long active();

    long activeRead();

    long activeWrite();

    long committed();

    long committedRead();

    long committedWrite();

    long rolledBack();

    long rolledBackRead();

    long rolledBackWrite();

    long terminated();

    long terminatedRead();

    long terminatedWrite();

    /** The id of the last committed transaction; a read-only commit takes no id. */
    long lastCommittedTransactionId();

    /** The id of the last closed transaction (the highest gap-free closed id). */
    long lastClosedTransactionId();

    /**
     * Takes the database's single transaction size callback slot: Neo4j then reports the heap and native size in
     * bytes of every transaction as it closes, on the closing thread.
     */
    void observeTransactionSizes(LongConsumer heapBytes, LongConsumer nativeBytes);

    /** Gives the transaction size callback slot back. */
    void stopObservingTransactionSizes();
}
