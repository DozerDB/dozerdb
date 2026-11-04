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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.JobScheduler;

/**
 * Adapts Neo4j's {@link JobScheduler} to {@link ExporterScheduler} for one scheduler group. Neo4j already
 * defines {@link Group#METRICS_CSV_WRITE} ({@code MetricsCsvWrite}) and {@link Group#METRICS_GRAPHITE_WRITE}
 * for its own metrics exporters and leaves them unused in Community; DozerDB's exporters run on them.
 */
public final class JobSchedulerAdapter implements ExporterScheduler {

    private final JobScheduler scheduler;
    private final Group group;

    private JobSchedulerAdapter(JobScheduler scheduler, Group group) {
        this.scheduler = scheduler;
        this.group = group;
    }

    /** The scheduler of the CSV exporter: Neo4j's {@code MetricsCsvWrite} group. */
    public static ExporterScheduler csvWrites(JobScheduler scheduler) {
        return new JobSchedulerAdapter(scheduler, Group.METRICS_CSV_WRITE);
    }

    /** The scheduler of the Graphite exporter: Neo4j's {@code MetricsGraphiteWrite} group. */
    public static ExporterScheduler graphiteWrites(JobScheduler scheduler) {
        return new JobSchedulerAdapter(scheduler, Group.METRICS_GRAPHITE_WRITE);
    }

    @Override
    public ScheduledReport scheduleRecurring(Runnable report, Duration interval) {
        long nanos = interval.toNanos();
        JobHandle<?> handle = scheduler.scheduleRecurring(group, report, nanos, nanos, TimeUnit.NANOSECONDS);
        return handle::cancel;
    }
}
