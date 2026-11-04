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
 * One database's Cypher query cache statistics as the Cypher collector reads them: Neo4j's own counters, read at
 * report time through the Java interfaces the Cypher engine satisfies per database ({@code QueryCacheStatistics} and
 * one {@code CacheMetrics} per cache kind). The Scala cache tracers behind them are never named. The cache kinds are
 * the five the Operations Manual 2026.10 documents, spelled as Neo4j's {@code metricsPerCacheKind()} keys them at
 * 2026.10.0: {@code preparser}, {@code ast}, {@code logical_plan}, {@code execution_plan} and
 * {@code executable_query}. A database whose statistics are not satisfied (one without a Cypher engine) reads zero
 * for everything until they are.
 */
public interface QueryCacheStats {

    /** The number of times the executable query cache found a plan stale and replanned the query. */
    long replans();

    /** The accumulated seconds between a plan's creation and the replan that replaced it. */
    long replanWaitTimeSeconds();

    /** The counters of one cache kind; a kind Neo4j does not report reads zero for everything. */
    CacheKindStats cache(String kind);

    /** The counters of one of the five query caches of a database. */
    interface CacheKindStats {

        /** The current number of entries. */
        long entries();

        long hits();

        long misses();

        /**
         * The number of times a cached query was replaced by a recompilation with expression code generation once
         * it had been hit often enough (Neo4j's {@code getCompiledWithExpressionCodeGen()}, the manual's
         * {@code compiled_total}); only the executable query cache does that, the other kinds read zero. A first
         * compilation into the cache is not one: Neo4j's {@code getCompiled()} counts both and is not read.
         */
        long recompilationsWithExpressionCodeGen();

        /** Entries evicted or removed, including those a flush removed. */
        long discards();

        /** Entries found stale and replanned; only the two plan caches check staleness. */
        long staleEntries();

        /** The number of times the whole cache was cleared. */
        long cacheFlushes();
    }
}
