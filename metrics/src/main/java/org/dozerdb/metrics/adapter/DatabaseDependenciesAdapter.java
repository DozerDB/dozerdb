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

import org.neo4j.common.DependencyResolver;
import org.neo4j.kernel.api.database.DatabaseSizeService;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.index.IndexCounters;
import org.neo4j.kernel.impl.store.stats.StoreEntityCounters;
import org.neo4j.kernel.impl.transaction.stats.CheckpointCounters;
import org.neo4j.kernel.impl.transaction.stats.TransactionCounters;
import org.neo4j.memory.MemoryPools;
import org.neo4j.monitoring.Monitors;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.wal.TransactionLogCounters;

/**
 * Adapts one started database's dependency resolver ({@code DatabaseContext.dependencies()}, a child of the
 * global one) to {@link DatabaseDependencies}; every hook is resolved when asked for.
 */
final class DatabaseDependenciesAdapter implements DatabaseDependencies {

    private final String databaseName;
    private final DependencyResolver dependencies;

    DatabaseDependenciesAdapter(String databaseName, DependencyResolver dependencies) {
        this.databaseName = databaseName;
        this.dependencies = dependencies;
    }

    @Override
    public String databaseName() {
        return databaseName;
    }

    @Override
    public TransactionStats transactionStats() {
        return new TransactionStatsAdapter(
                dependencies.resolveDependency(TransactionCounters.class),
                dependencies.resolveDependency(TransactionIdStore.class));
    }

    @Override
    public EntityCounts entityCounts() {
        return new EntityCountsAdapter(dependencies.resolveDependency(StoreEntityCounters.class));
    }

    /** The size service is global and the database id is the database's own; both resolve from the child resolver. */
    @Override
    public StoreSize storeSize() {
        return new StoreSizeAdapter(
                dependencies.resolveDependency(DatabaseSizeService.class),
                dependencies.resolveDependency(NamedDatabaseId.class));
    }

    /** The global pools are a global dependency, reached through the database resolver's parent chain. */
    @Override
    public MemoryPoolStats memoryPools() {
        return MemoryPoolsAdapter.of(dependencies.resolveDependency(MemoryPools.class));
    }

    @Override
    public CheckpointStats checkpointStats() {
        return new CheckpointStatsAdapter(dependencies.resolveDependency(CheckpointCounters.class));
    }

    @Override
    public TransactionLogStats transactionLogStats() {
        return new TransactionLogStatsAdapter(dependencies.resolveDependency(TransactionLogCounters.class));
    }

    /** The database's own monitor bus ({@code DatabaseMonitors}), which {@code AbstractDatabase} satisfies here. */
    @Override
    public QueryExecutions queryExecutions() {
        return new QueryExecutionMonitorAdapter(dependencies.resolveDependency(Monitors.class));
    }

    /** Resolved on first read rather than here: a database without a Cypher engine never satisfies the statistics. */
    @Override
    public QueryCacheStats queryCacheStats() {
        return new QueryCacheStatsAdapter(dependencies);
    }

    @Override
    public IndexStats indexStats() {
        return new IndexStatsAdapter(dependencies.resolveDependency(IndexCounters.class));
    }
}
