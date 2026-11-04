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

/**
 * One database's checkpoint statistics as the checkpoint collector reads them: Neo4j's own counters, kept per
 * database by the database tracer ({@code CheckpointCounters}) as each checkpoint completes, read here at report
 * time. The accumulated values cover every checkpoint of the database since it started; the "last" values are
 * those of the checkpoint that completed most recently, and are zero until the first one has.
 */
public interface CheckpointStats {

    /** The number of checkpoints completed. */
    long events();

    /** The accumulated duration of all completed checkpoints, in milliseconds. */
    long totalTimeMillis();

    /** The duration of the last checkpoint, in milliseconds. */
    long lastDurationMillis();

    /** The pages the last checkpoint flushed: the pages dirtied since the checkpoint before it. */
    long lastPagesFlushed();

    /** The IOs the page cache issued to flush the last checkpoint's pages. */
    long lastIoPerformed();

    /** The bytes the page cache wrote while flushing, accumulated over every checkpoint of the database. */
    long flushedBytes();

    /**
     * The IO limit (IOs per second) the IO controller enforced during the last checkpoint; 0 when the controller is
     * disabled, as Community's is.
     */
    long lastIoLimit();

    /** The number of times the IO controller paused the last checkpoint; 0 when the controller is disabled. */
    long lastIoLimitedTimes();

    /** The milliseconds the IO controller paused the last checkpoint for; 0 when the controller is disabled. */
    long lastIoLimitedMillis();
}
