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

import java.util.function.ToLongFunction;
import org.neo4j.common.DependencyResolver;
import org.neo4j.kernel.impl.query.CacheMetrics;
import org.neo4j.kernel.impl.query.QueryCacheStatistics;

/**
 * Adapts Neo4j's per-database {@link QueryCacheStatistics} to {@link QueryCacheStats}. The Cypher engine provider
 * satisfies the statistics in the database's dependencies while the engine is created ({@code Database.specificStart},
 * before the database's lifecycle starts and its start event fires), so they are there when a database collector
 * registers; a database without a Cypher engine never satisfies them. The adapter therefore resolves them on first
 * use and reads {@link QueryCacheStatistics#EMPTY} (all zeros) until they are satisfied, rather than failing the
 * collector. The per-kind {@link CacheMetrics} are the entries of {@code metricsPerCacheKind()}, keyed by the
 * cache kind names Neo4j's Cypher engine uses, which are the manual's; each is looked up once and kept, since the
 * tracers behind them live as long as the engine. The system database's statistics combine its two engines'
 * caches, which Neo4j does by summing.
 */
final class QueryCacheStatsAdapter implements QueryCacheStats {

    private final DependencyResolver dependencies;
    private volatile QueryCacheStatistics resolved;

    QueryCacheStatsAdapter(DependencyResolver dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    public long replans() {
        return statistics().numberOfReplans();
    }

    @Override
    public long replanWaitTimeSeconds() {
        return statistics().replanWaitTime();
    }

    @Override
    public CacheKindStats cache(String kind) {
        return new KindStats(kind, entriesOf(kind));
    }

    /**
     * The statistics once satisfied; {@code EMPTY} until then, so a read never fails on their absence. Neo4j 5.26.32's
     * {@link DependencyResolver} has no {@code resolveOptionalDependency}, so presence is asked first. Its getters
     * return boxed {@code Long}s, which the Cypher engine never leaves null.
     */
    private QueryCacheStatistics statistics() {
        QueryCacheStatistics statistics = resolved;
        if (statistics == null) {
            if (!dependencies.containsDependency(QueryCacheStatistics.class)) {
                return QueryCacheStatistics.EMPTY;
            }
            statistics = dependencies.resolveDependency(QueryCacheStatistics.class);
            resolved = statistics;
        }
        return statistics;
    }

    /** The entry count of a cache kind lives on the statistics object itself, one method per kind. */
    private static ToLongFunction<QueryCacheStatistics> entriesOf(String kind) {
        return switch (kind) {
            case "preparser" -> QueryCacheStatistics::preParserCacheEntries;
            case "ast" -> QueryCacheStatistics::astCacheEntries;
            case "logical_plan" -> QueryCacheStatistics::logicalPlanCacheEntries;
            case "execution_plan" -> QueryCacheStatistics::executionPlanCacheEntries;
            case "executable_query" -> QueryCacheStatistics::executableQueryCacheEntries;
            default -> statistics -> 0L;
        };
    }

    private final class KindStats implements CacheKindStats {

        private final String kind;
        private final ToLongFunction<QueryCacheStatistics> entries;
        private volatile CacheMetrics metrics;

        private KindStats(String kind, ToLongFunction<QueryCacheStatistics> entries) {
            this.kind = kind;
            this.entries = entries;
        }

        @Override
        public long entries() {
            return entries.applyAsLong(statistics());
        }

        @Override
        public long hits() {
            return read(CacheMetrics::getHits);
        }

        @Override
        public long misses() {
            return read(CacheMetrics::getMisses);
        }

        @Override
        public long recompilationsWithExpressionCodeGen() {
            return read(CacheMetrics::getCompiledWithExpressionCodeGen);
        }

        @Override
        public long discards() {
            return read(CacheMetrics::getDiscards);
        }

        @Override
        public long staleEntries() {
            return read(CacheMetrics::getStaleEntries);
        }

        @Override
        public long cacheFlushes() {
            return read(CacheMetrics::getCacheFlushes);
        }

        private long read(ToLongFunction<CacheMetrics> counter) {
            CacheMetrics current = metrics;
            if (current == null) {
                current = statistics().metricsPerCacheKind().get(kind);
                if (current == null) {
                    return 0L;
                }
                metrics = current;
            }
            return counter.applyAsLong(current);
        }
    }
}
