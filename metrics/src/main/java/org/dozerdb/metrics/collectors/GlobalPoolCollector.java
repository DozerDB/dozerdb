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

import org.dozerdb.metrics.adapter.MemoryPoolStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Global neo4j pools metrics (Operations Manual 2026.10, "Global neo4j pools metrics"):
 * {@code pool.<pool>.used_heap}, {@code .used_native}, {@code .total_used}, {@code .total_size} and {@code .free}
 * for every global memory pool of the server, read from Neo4j's own pool accounting at report time. The pools are
 * those existing when the collector registers; Neo4j creates all of them, the connectors' included, before any
 * component starts.
 */
public final class GlobalPoolCollector implements Collector {

    public static final String NAME = "pool";

    private final MemoryPoolStats pools;

    public GlobalPoolCollector(MemoryPoolStats pools) {
        this.pools = pools;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        for (MemoryPoolStats.Pool pool : pools.globalPools()) {
            registerPool(metrics, NAME + "." + pool.name(), pool);
        }
    }

    /** The five documented gauges of one pool under {@code prefix}; the database collector shares them. */
    static void registerPool(MetricRegistration metrics, String prefix, MemoryPoolStats.Pool pool) {
        metrics.gauge(prefix + ".used_heap", pool::usedHeap);
        metrics.gauge(prefix + ".used_native", pool::usedNative);
        metrics.gauge(prefix + ".total_used", pool::totalUsed);
        metrics.gauge(prefix + ".total_size", pool::totalSize);
        metrics.gauge(prefix + ".free", pool::free);
    }
}
