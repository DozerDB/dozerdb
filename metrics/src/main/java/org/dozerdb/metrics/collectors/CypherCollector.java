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

import java.util.List;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.QueryCacheStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Cypher metrics (Operations Manual 2026.10, "Cypher metrics"): the 37 rows of one database, the two
 * {@code cypher.replan_*} counters and, for each of the five documented cache kinds, six
 * {@code cypher_cache_<kind>_*_total} counters and the {@code cypher_cache_<kind>_entries} gauge.
 * The names are the manual's, underscores included, because the manual spells them so in every row: the {@code _total} is part of the dotted name, so the Prometheus
 * exposition adds its counter suffix to it ({@code ..._hits_total_total}), and Neo4j's default
 * filter entry {@code *cypher.cache*} matches none of them, which leaves only {@code cypher.replan_events} enabled by
 * default. The values are Neo4j's own counters, read at report time: {@code compiled_total} counts
 * recompilations with expression code generation, the manual's description, not every compilation. The execution
 * plan cache's hits, misses, discards and flushes count only when {@code internal.cypher.enable_runtime_monitors}
 * attaches its tracer, as in Neo4j. One instance per started database, built from that database's dependencies.
 */
public final class CypherCollector implements Collector {

    public static final String GROUP = "cypher";

    /** The documented cache kinds, which are also the keys Neo4j's {@code metricsPerCacheKind()} returns. */
    public static final List<String> CACHE_KINDS =
            List.of("ast", "executable_query", "execution_plan", "logical_plan", "preparser");

    private final DatabaseDependencies database;

    public CypherCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        QueryCacheStats stats = database.queryCacheStats();
        metrics.counter("cypher.replan_events", stats::replans);
        metrics.counter("cypher.replan_wait_time", stats::replanWaitTimeSeconds);
        for (String kind : CACHE_KINDS) {
            QueryCacheStats.CacheKindStats cache = stats.cache(kind);
            String prefix = "cypher_cache_" + kind + "_";
            metrics.counter(prefix + "cache_flushes_total", cache::cacheFlushes);
            metrics.counter(prefix + "compiled_total", cache::recompilationsWithExpressionCodeGen);
            metrics.counter(prefix + "discards_total", cache::discards);
            metrics.gauge(prefix + "entries", cache::entries);
            metrics.counter(prefix + "hits_total", cache::hits);
            metrics.counter(prefix + "misses_total", cache::misses);
            metrics.counter(prefix + "stale_entries_total", cache::staleEntries);
        }
    }
}
