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
 * The server's page cache statistics as the page cache collector reads them: the global page cache tracer's own
 * monotonic counters ({@code PageCacheCounters}, kept by Neo4j's default tracer in {@code LongAdder}s on the
 * threads that pin, fault, evict and flush pages), read here at report time, plus the page cache's page counts for
 * the usage ratio. One instance per server.
 *
 * <p>Some counters are only ever driven by mechanisms Community does not ship (the checkpoint IO limiter, the page
 * prefetcher, no-fault cursors, multi-version page copies); they stay zero on DozerDB and their inventory rows say
 * so. The snapshot and asynchronous IO counts a later Neo4j adds have no row in the Operations Manual 5 (and 5.26.32
 * has no asynchronous IO counters at all), so they are not read here.
 */
public interface PageCacheStats {

    /** Page faults, successful and failed, caused by pins and by vectored faults alike. */
    long faults();

    long failedFaults();

    /** Pins that found no page and did not fault because the cursor was opened no-fault; never on Community. */
    long cancelledFaults();

    /** Vectored faults, each loading several pages; only the page prefetcher issues them, never on Community. */
    long vectoredFaults();

    long failedVectoredFaults();

    /** Pages loaded by vectored faults rather than by pins; never on Community. */
    long noPinFaults();

    long evictions();

    /** Evictions a pinning thread performed itself because the free list was empty. */
    long cooperativeEvictions();

    long pins();

    long unpins();

    long hits();

    long flushes();

    /** Pages flushed because they were evicted dirty. */
    long evictionFlushes();

    long cooperativeEvictionFlushes();

    /** Consecutive dirty pages merged into one write during a flush. */
    long merges();

    long bytesRead();

    long bytesWritten();

    long evictionExceptions();

    /** Hits over lookups, as Neo4j derives it: hits divided by hits plus the faults that pins caused. */
    double hitRatio();

    /** IO operations the flushes performed. */
    long ioOperations();

    /** Times the checkpoint IO limiter throttled a flush; Community has no limiter, so never. */
    long throttledTimes();

    long throttledMillis();

    /** Multi-version page copies; never on Community. */
    long pagesCopied();

    /** The share of the page cache's pages holding data: used pages over all pages, or zero without pages. */
    double usageRatio();
}
