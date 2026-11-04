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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.dozerdb.metrics.adapter.Databases;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database operation and state counts (Operations Manual 2026.10, "Database operation count metrics" and
 * "Database state count metrics", both global: {@code <prefix>.dbms.db.operation.count.<operation>} and
 * {@code <prefix>.dbms.db.state.count.<state>}). Neo4j Community declares counters for both groups but never
 * drives them, so DozerDB keeps the operation counts itself, as event counters fed by the public database events,
 * and reads nothing from those counters. A create, start, stop, drop and panic is one event each; a create is a
 * database initialised without a store (the adapter does not announce the initialisation of an existing database,
 * so a server that starts again counts no create). A database created before the metrics start (the system
 * database of a new home) is not counted. A {@code DROP} of an online database stops it first, which counts as a
 * stop as well as a drop. A failed operation is a panic, the one failure the public events report;
 * a recovered one is the next successful start of a database that panicked. The state counts are gauges read at
 * report time from the server's database states through the adapter. One instance per server; the databases are
 * resolved when the collector registers, inside its isolation boundary.
 */
public final class DatabaseCountsCollector implements Collector, Databases.Listener {

    public static final String NAME = "db";

    private static final LongConsumer NONE = ignored -> {};

    private final Supplier<Databases> source;
    private volatile LongConsumer creates = NONE;
    private volatile LongConsumer starts = NONE;
    private volatile LongConsumer stops = NONE;
    private volatile LongConsumer drops = NONE;
    private volatile LongConsumer failures = NONE;
    private volatile LongConsumer recoveries = NONE;

    /** The databases that panicked and have not started again or been dropped since. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public DatabaseCountsCollector(Supplier<Databases> source) {
        this.source = source;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        Databases databases = source.get();
        creates = metrics.eventCounter("db.operation.count.create");
        starts = metrics.eventCounter("db.operation.count.start");
        stops = metrics.eventCounter("db.operation.count.stop");
        drops = metrics.eventCounter("db.operation.count.drop");
        failures = metrics.eventCounter("db.operation.count.failed");
        recoveries = metrics.eventCounter("db.operation.count.recovered");
        metrics.gauge("db.state.count.hosted", () -> databases.stateCounts().hosted());
        metrics.gauge("db.state.count.failed", () -> databases.stateCounts().failed());
        metrics.gauge(
                "db.state.count.desired_started", () -> databases.stateCounts().desiredStarted());
        databases.addListener(this);
        metrics.onRetire(() -> databases.removeListener(this));
    }

    @Override
    public void databaseCreated(String databaseName) {
        creates.accept(1);
    }

    @Override
    public void databaseStarted(String databaseName) {
        starts.accept(1);
        if (failed.remove(databaseName)) {
            recoveries.accept(1);
        }
    }

    @Override
    public void databaseStopped(String databaseName) {
        stops.accept(1);
    }

    @Override
    public void databaseDropped(String databaseName) {
        failed.remove(databaseName);
        drops.accept(1);
    }

    @Override
    public void databasePanicked(String databaseName) {
        failed.add(databaseName);
        failures.accept(1);
    }
}
