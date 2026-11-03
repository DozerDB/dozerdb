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

import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.store.stats.StoreEntityCounters;

/**
 * Adapts Neo4j's per-database {@link StoreEntityCounters} (the storage engine's, satisfied in the database's
 * dependencies by {@code Database}; {@code RecordDatabaseEntityCounters} for the record format) to
 * {@link EntityCounts}. The counts store reads ({@code allNodesCountStore}, {@code allRelationshipsCountStore})
 * are tree lookups made with the null cursor context at report time; the ids in use are the id generators' high
 * ids, read without IO. Every call happens on an exporter's thread, never per event.
 */
final class EntityCountsAdapter implements EntityCounts {

    private final StoreEntityCounters counters;

    EntityCountsAdapter(StoreEntityCounters counters) {
        this.counters = counters;
    }

    @Override
    public long nodes() {
        return counters.allNodesCountStore(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long relationships() {
        return counters.allRelationshipsCountStore(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long relationshipTypes() {
        return counters.relationshipTypes(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long nodeIdsInUse() {
        return counters.nodes(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long relationshipIdsInUse() {
        return counters.relationships(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long propertyIdsInUse() {
        return counters.properties(CursorContext.NULL_CONTEXT);
    }

    @Override
    public long relationshipTypeIdsInUse() {
        return counters.relationshipTypes(CursorContext.NULL_CONTEXT);
    }
}
