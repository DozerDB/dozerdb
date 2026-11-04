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
 * One database's index statistics as the index collector reads them: Neo4j's own in-memory counters per index type
 * ({@code IndexCounters}, kept per database by {@code DatabaseIndexStats}), read here at report time. A population
 * is counted when a population job brings an index online; a query is counted for every read of an online index, in
 * batches that Neo4j's index usage report job delivers every {@code internal.dbms.index.usage_report_frequency}
 * (10 s by default), so reads of an index dropped before its next report are lost with it. Dropping an index lowers
 * neither count. The counters live in memory with the database object, which DozerDB keeps across {@code STOP} and
 * {@code START}, so they survive a database restart and reset when the server restarts.
 */
public interface IndexStats {

    /** The number of times indexes of the type have been queried. */
    long queried(IndexType type);

    /** The number of population jobs completed for indexes of the type. */
    long populated(IndexType type);

    /**
     * The index types the Operations Manual 2026.10 documents a metric pair for, with the {@code <type>} segment of
     * {@code index.<type>.queried} and {@code index.<type>.populated} as the manual spells it. The adapter maps each
     * to Neo4j's own index type, so a Neo4j that renames or drops one fails to compile the adapter rather than
     * emitting a metric under a new name.
     */
    enum IndexType {
        FULLTEXT("fulltext"),
        LOOKUP("lookup"),
        TEXT("text"),
        RANGE("range"),
        POINT("point"),
        VECTOR("vector");

        private final String segment;

        IndexType(String segment) {
            this.segment = segment;
        }

        /** The {@code <type>} segment of the metric names. */
        public String segment() {
            return segment;
        }
    }
}
