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
 * The Neo4j services of one started database that database collectors read. Each is resolved from the database's
 * own dependency resolver when asked for, so a hook that is missing fails only the collector asking for it, inside
 * that collector's isolation boundary.
 */
public interface DatabaseDependencies {

    String databaseName();

    /** The database's transaction counters, transaction ids and transaction size callback. */
    TransactionStats transactionStats();

    /** The database's node, relationship and relationship type counts and the ids in use of its stores. */
    EntityCounts entityCounts();

    /** The database's size on disk, from the server's database size service. */
    StoreSize storeSize();

    /** The server's memory pools, from which the database's own pools are selected by its name. */
    MemoryPoolStats memoryPools();

    /** The database's checkpoint counters, from its database tracer. */
    CheckpointStats checkpointStats();

    /** The database's transaction log counters, from its database tracer. */
    TransactionLogStats transactionLogStats();

    /** The end of every query of the database, announced on the database's own monitor bus. */
    QueryExecutions queryExecutions();

    /** The database's Cypher query cache and replan counters, from its Cypher engine's query cache statistics. */
    QueryCacheStats queryCacheStats();

    /** The database's per-type index counters. */
    IndexStats indexStats();
}
