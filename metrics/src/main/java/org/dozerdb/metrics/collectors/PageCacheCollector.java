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

import java.util.function.Supplier;
import org.dozerdb.metrics.adapter.PageCacheStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Page cache metrics (Operations Manual 2026.10, "Database page cache metrics"): the 28 {@code page_cache.*}
 * rows, global despite the section's heading ({@code <prefix>.dbms.page_cache.<metric>}). The counters are
 * Neo4j's own page cache counters exposed as counters read at report time; {@code hit_ratio} is Neo4j's own
 * derivation (hits over hits plus the faults pins caused) and {@code usage_ratio} the used share of the page
 * cache's pages. One instance per server. The statistics are resolved when the collector registers, inside its
 * isolation boundary, so a missing hook disables this collector alone.
 */
public final class PageCacheCollector implements Collector {

    public static final String NAME = "page_cache";

    private final Supplier<PageCacheStats> source;

    public PageCacheCollector(Supplier<PageCacheStats> source) {
        this.source = source;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        PageCacheStats stats = source.get();
        metrics.counter("page_cache.eviction_exceptions", stats::evictionExceptions);
        metrics.counter("page_cache.flushes", stats::flushes);
        metrics.counter("page_cache.merges", stats::merges);
        metrics.counter("page_cache.unpins", stats::unpins);
        metrics.counter("page_cache.pins", stats::pins);
        metrics.counter("page_cache.evictions", stats::evictions);
        metrics.counter("page_cache.evictions.cooperative", stats::cooperativeEvictions);
        metrics.counter("page_cache.eviction.flushes", stats::evictionFlushes);
        metrics.counter("page_cache.eviction.cooperative.flushes", stats::cooperativeEvictionFlushes);
        metrics.counter("page_cache.page_faults", stats::faults);
        metrics.counter("page_cache.page_fault_failures", stats::failedFaults);
        metrics.counter("page_cache.page_cancelled_faults", stats::cancelledFaults);
        metrics.counter("page_cache.page_vectored_faults", stats::vectoredFaults);
        metrics.counter("page_cache.page_vectored_faults_failures", stats::failedVectoredFaults);
        metrics.counter("page_cache.page_no_pin_page_faults", stats::noPinFaults);
        metrics.counter("page_cache.hits", stats::hits);
        metrics.doubleGauge("page_cache.hit_ratio", stats::hitRatio);
        metrics.doubleGauge("page_cache.usage_ratio", stats::usageRatio);
        metrics.counter("page_cache.bytes_read", stats::bytesRead);
        metrics.counter("page_cache.bytes_written", stats::bytesWritten);
        metrics.counter("page_cache.iops", stats::ioOperations);
        metrics.counter("page_cache.throttled.times", stats::throttledTimes);
        metrics.counter("page_cache.throttled.millis", stats::throttledMillis);
        metrics.counter("page_cache.pages_copied", stats::pagesCopied);
        metrics.counter("page_cache.pages_snapshot_loaded", stats::snapshotsLoaded);
        metrics.counter("page_cache.async_submit", stats::asyncIoSubmitted);
        metrics.counter("page_cache.async_complete", stats::asyncIoCompleted);
        metrics.counter("page_cache.async_failure", stats::asyncIoFailed);
    }
}
