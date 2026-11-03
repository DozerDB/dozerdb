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

import java.util.List;

/**
 * Neo4j's memory pools as the pool collectors read them: the global pools of the server, one per memory group
 * (transaction, Bolt, HTTP, page cache, ...), and the pools a database holds within them (its transaction and
 * other pools). Every value is Neo4j's own accounting, read at report time.
 */
public interface MemoryPoolStats {

    /** The server's global pools as they exist now, one per memory group that has one. */
    List<Pool> globalPools();

    /** The named database's pools as they exist now. */
    List<Pool> databasePools(String databaseName);

    /** One pool. {@link #name()} is the {@code <pool>} segment of the metric names: the memory group in metric spelling. */
    interface Pool {

        String name();

        /** Used or reserved heap memory in bytes. */
        long usedHeap();

        /** Used or reserved native memory in bytes. */
        long usedNative();

        /** Used heap and native memory in bytes. */
        long totalUsed();

        /** The pool's capacity (heap and native) in bytes; a pool without a limit reports the largest possible value. */
        long totalSize();

        /** Unused memory in bytes. */
        long free();
    }
}
