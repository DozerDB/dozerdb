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
 * One database's transaction log statistics as the transaction log collector reads them: Neo4j's own counters, kept
 * per database by the database tracer ({@code TransactionLogCounters}) as the log is appended to, flushed and
 * rotated, read here at report time. The accumulated values cover the database's whole lifetime since it started;
 * the "last" values describe the most recent event and are zero until the first one.
 */
public interface TransactionLogStats {

    /** The number of log rotations completed (transaction log and checkpoint log alike, as Neo4j counts them). */
    long rotations();

    /** The accumulated duration of all completed log rotations, in milliseconds. */
    long rotationTotalTimeMillis();

    /** The duration of the last log rotation, in milliseconds. */
    long lastRotationDurationMillis();

    /** The bytes appended to the transaction log (transaction commands, log headers and checkpoint records). */
    long appendedBytes();

    /** The number of times a transaction log file was flushed to disk. */
    long flushes();

    /** The number of transactions in the last batch appended to the log. */
    long lastAppendBatchSize();
}
