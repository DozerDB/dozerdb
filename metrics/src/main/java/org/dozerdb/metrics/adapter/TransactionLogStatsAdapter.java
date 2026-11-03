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

import org.neo4j.kernel.impl.transaction.log.TransactionLogCounters;

/**
 * Adapts Neo4j's per-database {@link TransactionLogCounters} (in {@code org.neo4j.kernel.impl.transaction.log} at Neo4j
 * 5.26.32; a later Neo4j moves it to {@code org.neo4j.wal}) to {@link TransactionLogStats}. The counters are a face
 * of the database's {@code DatabaseTracer} ({@code DefaultDatabaseTracer} with the default tracer factory, whose
 * append, flush and rotate events fill them), which {@code Database} satisfies in the database's dependencies, so
 * they are resolved by this interface rather than through the tracer composition, the part of Neo4j's tracing most likely
 * to change. Every documented transaction log row has a counter here; the {@code LogRotationMonitor} is not needed.
 */
final class TransactionLogStatsAdapter implements TransactionLogStats {

    private final TransactionLogCounters counters;

    TransactionLogStatsAdapter(TransactionLogCounters counters) {
        this.counters = counters;
    }

    @Override
    public long rotations() {
        return counters.numberOfLogRotations();
    }

    @Override
    public long rotationTotalTimeMillis() {
        return counters.logRotationAccumulatedTotalTimeMillis();
    }

    @Override
    public long lastRotationDurationMillis() {
        return counters.lastLogRotationTimeMillis();
    }

    @Override
    public long appendedBytes() {
        return counters.appendedBytes();
    }

    @Override
    public long flushes() {
        return counters.numberOfFlushes();
    }

    @Override
    public long lastAppendBatchSize() {
        return counters.lastTransactionLogAppendBatch();
    }
}
