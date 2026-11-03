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

import org.dozerdb.metrics.adapter.CheckpointStats;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database checkpointing metrics (Operations Manual 5, "Database checkpointing metrics"): the
 * {@code check_point.*} rows of one database. The accumulated values are Neo4j's own counters, kept by the database
 * tracer as each checkpoint completes and exposed as counters read at report time; the values of the last checkpoint
 * are gauges over the same object. The three IO limiter rows ({@code io_limit}, {@code limit_times},
 * {@code limit_millis}) read Neo4j's values too, which are 0 in Community because its IO controller is disabled. One
 * instance per started database, built from that database's dependencies.
 */
public final class CheckpointCollector implements Collector {

    public static final String GROUP = "check_point";

    private final DatabaseDependencies database;

    public CheckpointCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        CheckpointStats stats = database.checkpointStats();
        metrics.counter("check_point.events", stats::events);
        metrics.counter("check_point.total_time", stats::totalTimeMillis);
        metrics.gauge("check_point.duration", stats::lastDurationMillis);
        metrics.gauge("check_point.pages_flushed", stats::lastPagesFlushed);
        metrics.gauge("check_point.io_performed", stats::lastIoPerformed);
        metrics.counter("check_point.flushed_bytes", stats::flushedBytes);
        metrics.gauge("check_point.io_limit", stats::lastIoLimit);
        metrics.gauge("check_point.limit_times", stats::lastIoLimitedTimes);
        metrics.gauge("check_point.limit_millis", stats::lastIoLimitedMillis);
    }
}
