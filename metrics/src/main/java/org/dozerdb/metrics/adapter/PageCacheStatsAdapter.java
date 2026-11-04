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

import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.monitoring.PageCacheCounters;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;

/**
 * Adapts the global {@link PageCacheTracer} (the one {@code GlobalModule} builds through the configured
 * {@code TracerFactory} and satisfies as a global dependency; {@code DefaultPageCacheTracer} by default, which
 * counts every event in {@code LongAdder}s) and the global {@link PageCache} (for the used and free page counts)
 * to {@link PageCacheStats}. The tracer is read through its pull interface {@link PageCacheCounters}; nothing is
 * installed and {@code internal.dbms.tracer} is not touched.
 */
public final class PageCacheStatsAdapter implements PageCacheStats {

    private final PageCacheCounters counters;
    private final PageCache pageCache;

    PageCacheStatsAdapter(PageCacheCounters counters, PageCache pageCache) {
        this.counters = counters;
        this.pageCache = pageCache;
    }

    /** Resolves the tracer and the page cache from the server's dependencies; call it inside the collector's boundary. */
    public static PageCacheStats of(Neo4jDependencies dependencies) {
        return new PageCacheStatsAdapter(dependencies.pageCacheTracer(), dependencies.pageCache());
    }

    @Override
    public long faults() {
        return counters.faults();
    }

    @Override
    public long failedFaults() {
        return counters.failedFaults();
    }

    @Override
    public long cancelledFaults() {
        return counters.noFaults();
    }

    @Override
    public long vectoredFaults() {
        return counters.vectoredFaults();
    }

    @Override
    public long failedVectoredFaults() {
        return counters.failedVectoredFaults();
    }

    @Override
    public long noPinFaults() {
        return counters.noPinFaults();
    }

    @Override
    public long evictions() {
        return counters.evictions();
    }

    @Override
    public long cooperativeEvictions() {
        return counters.cooperativeEvictions();
    }

    @Override
    public long pins() {
        return counters.pins();
    }

    @Override
    public long unpins() {
        return counters.unpins();
    }

    @Override
    public long hits() {
        return counters.hits();
    }

    @Override
    public long flushes() {
        return counters.flushes();
    }

    @Override
    public long evictionFlushes() {
        return counters.evictionFlushes();
    }

    @Override
    public long cooperativeEvictionFlushes() {
        return counters.cooperativeEvictionFlushes();
    }

    @Override
    public long merges() {
        return counters.merges();
    }

    @Override
    public long bytesRead() {
        return counters.bytesRead();
    }

    @Override
    public long bytesWritten() {
        return counters.bytesWritten();
    }

    @Override
    public long evictionExceptions() {
        return counters.evictionExceptions();
    }

    @Override
    public double hitRatio() {
        return counters.hitRatio();
    }

    @Override
    public long ioOperations() {
        return counters.iopqPerformed();
    }

    @Override
    public long throttledTimes() {
        return counters.ioLimitedTimes();
    }

    @Override
    public long throttledMillis() {
        return counters.ioLimitedMillis();
    }

    @Override
    public long pagesCopied() {
        return counters.copiedPages();
    }

    @Override
    public long snapshotsLoaded() {
        return counters.snapshotsLoaded();
    }

    @Override
    public long asyncIoSubmitted() {
        return counters.asyncIoSubmitted();
    }

    @Override
    public long asyncIoCompleted() {
        return counters.asyncIoCompleted();
    }

    @Override
    public long asyncIoFailed() {
        return counters.asyncIoFailed();
    }

    @Override
    public double usageRatio() {
        long maxPages = pageCache.maxCachedPages();
        if (maxPages <= 0) {
            return 0d;
        }
        return (double) (maxPages - pageCache.freePages()) / maxPages;
    }
}
