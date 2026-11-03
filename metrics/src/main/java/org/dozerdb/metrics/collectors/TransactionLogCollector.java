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

import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.TransactionLogStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database transaction log metrics (Operations Manual 5, "Database transaction log metrics"): the {@code log.*}
 * rows of one database. The accumulated values are Neo4j's own counters, kept by the database tracer as the log is
 * appended to, flushed and rotated, exposed as counters read at report time; the values of the last event are gauges
 * over the same object. One instance per started database, built from that database's dependencies.
 */
public final class TransactionLogCollector implements Collector {

    public static final String GROUP = "log";

    private final DatabaseDependencies database;

    public TransactionLogCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        TransactionLogStats stats = database.transactionLogStats();
        metrics.counter("log.rotation_events", stats::rotations);
        metrics.counter("log.rotation_total_time", stats::rotationTotalTimeMillis);
        metrics.gauge("log.rotation_duration", stats::lastRotationDurationMillis);
        metrics.counter("log.appended_bytes", stats::appendedBytes);
        metrics.counter("log.flushes", stats::flushes);
        metrics.gauge("log.append_batch_size", stats::lastAppendBatchSize);
    }
}
