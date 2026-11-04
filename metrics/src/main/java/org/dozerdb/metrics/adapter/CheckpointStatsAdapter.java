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

import org.neo4j.kernel.impl.transaction.stats.CheckpointCounters;

/**
 * Adapts Neo4j's per-database {@link CheckpointCounters} to {@link CheckpointStats}. The counters are a face of the
 * database's {@code DatabaseTracer} ({@code DefaultDatabaseTracer} with the default tracer factory; its
 * {@code CountingLogCheckPointEvent} fills them when {@code CheckPointerImpl} completes a checkpoint), which
 * {@code Database} satisfies in the database's dependencies, so they are resolved by this interface rather than
 * through the tracer composition, which is the part of Neo4j's tracing most likely to change.
 */
final class CheckpointStatsAdapter implements CheckpointStats {

    private final CheckpointCounters counters;

    CheckpointStatsAdapter(CheckpointCounters counters) {
        this.counters = counters;
    }

    @Override
    public long events() {
        return counters.numberOfCheckPoints();
    }

    @Override
    public long totalTimeMillis() {
        return counters.checkPointAccumulatedTotalTimeMillis();
    }

    @Override
    public long lastDurationMillis() {
        return counters.lastCheckpointTimeMillis();
    }

    @Override
    public long lastPagesFlushed() {
        return counters.lastCheckpointPagesFlushed();
    }

    @Override
    public long lastIoPerformed() {
        return counters.lastCheckpointIOs();
    }

    @Override
    public long flushedBytes() {
        return counters.flushedBytes();
    }

    @Override
    public long lastIoLimit() {
        return counters.lastCheckpointIOLimit();
    }

    @Override
    public long lastIoLimitedTimes() {
        return counters.lastCheckpointIOLimitedTimes();
    }

    @Override
    public long lastIoLimitedMillis() {
        return counters.lastCheckpointIOLimitedMillis();
    }
}
