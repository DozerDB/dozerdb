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

import org.neo4j.kernel.impl.index.IndexCounters;

/**
 * Adapts Neo4j's per-database {@link IndexCounters} to {@link IndexStats}. The counters are {@code DatabaseIndexStats},
 * one per database, which {@code Database} satisfies in the database's dependencies and adds as a listener on the
 * database's monitors: its {@code populationCompleteOn} ({@code IndexMonitor}) is called when a populating index
 * flips online, and its query counts are added by {@code OnlineIndexProxy.reportUsageStatistics} from each index's
 * usage tracker on the {@code IndexingService}'s usage report job. The counters are resolved by this interface, never
 * by the class. {@code IndexCounters} and the {@code IndexType} names are among the hooks most likely to change;
 * the mapping below names every documented type against Neo4j's enum so a change fails at compile time.
 */
final class IndexStatsAdapter implements IndexStats {

    private final IndexCounters counters;

    IndexStatsAdapter(IndexCounters counters) {
        this.counters = counters;
    }

    @Override
    public long queried(IndexType type) {
        return counters.getQueryCount(neo4jType(type));
    }

    @Override
    public long populated(IndexType type) {
        return counters.getPopulationCount(neo4jType(type));
    }

    /** The documented type's Neo4j index type ({@code org.neo4j.internal.schema.IndexType}). */
    static org.neo4j.internal.schema.IndexType neo4jType(IndexType type) {
        return switch (type) {
            case FULLTEXT -> org.neo4j.internal.schema.IndexType.FULLTEXT;
            case LOOKUP -> org.neo4j.internal.schema.IndexType.LOOKUP;
            case TEXT -> org.neo4j.internal.schema.IndexType.TEXT;
            case RANGE -> org.neo4j.internal.schema.IndexType.RANGE;
            case POINT -> org.neo4j.internal.schema.IndexType.POINT;
            case VECTOR -> org.neo4j.internal.schema.IndexType.VECTOR;
        };
    }
}
