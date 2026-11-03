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

/**
 * The module's view of Neo4j's job scheduler: runs an exporter's periodic report on the scheduler group Neo4j
 * defines for that channel, so no metric is read and no byte is written on a database thread.
 */
public interface ExporterScheduler {

    /** Runs {@code report} every {@code interval}, the first time after one interval, until cancelled. */
    ScheduledReport scheduleRecurring(Runnable report, Duration interval);

    /** The handle of a scheduled report. */
    @FunctionalInterface
    interface ScheduledReport {
        void cancel();
    }
}
