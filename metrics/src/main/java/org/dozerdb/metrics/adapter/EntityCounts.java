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
 * One database's entity counts as the entity count collector reads them at report time: the counts of nodes and
 * relationships the store keeps in its counts store, and the ids in use of the store's id generators (the next
 * free id of each record type), which is what the manual's "internally generated IDs" are. All come from Neo4j's
 * store entity counters; their meaning depends on the store format (record format on DozerDB).
 */
public interface EntityCounts {

    /** The number of nodes in the database, from the counts store. */
    long nodes();

    /** The number of relationships in the database, from the counts store. */
    long relationships();

    /** The number of relationship type ids generated so far; a type is never removed, so this is the type count. */
    long relationshipTypes();

    /** Node ids in use: the next node id the store would hand out. */
    long nodeIdsInUse();

    /** Relationship ids in use: the next relationship id the store would hand out. */
    long relationshipIdsInUse();

    /** Property record ids in use; the manual notes a block-format store reports -1 here. */
    long propertyIdsInUse();

    /** Relationship type token ids in use, the same quantity as {@link #relationshipTypes()}. */
    long relationshipTypeIdsInUse();
}
