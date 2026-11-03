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

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.neo4j.memory.GlobalMemoryGroupTracker;
import org.neo4j.memory.MemoryPools;
import org.neo4j.memory.ScopedMemoryPool;

/**
 * Adapts Neo4j's {@link MemoryPools} (satisfied in the global dependencies by {@code GlobalModule}) to
 * {@link MemoryPoolStats}. A global pool is a {@link GlobalMemoryGroupTracker} per {@code MemoryGroup}; a database's
 * pools are the {@link ScopedMemoryPool}s a global pool created for that database name ({@code Database} creates a
 * transaction pool and an "other" pool per database). The {@code <pool>} name is the group's display name
 * ({@code "Page Cache"}, {@code "HTTP Transaction"}) lower-cased with spaces as underscores, which gives the
 * manual's {@code bolt} and {@code transaction}.
 *
 * <p>The pools of the connectors are created when the Bolt server and the web server are initialised, which Neo4j's
 * lifecycle does before any component, this extension included, is started; a pool created after the pool collector
 * registered is therefore not expected and would not be reported. The collector pins that order with a test.
 */
public final class MemoryPoolsAdapter implements MemoryPoolStats {

    private final Supplier<MemoryPools> pools;

    private MemoryPoolsAdapter(Supplier<MemoryPools> pools) {
        this.pools = pools;
    }

    /** The global pools, resolved from the global dependencies when asked for. */
    public static MemoryPoolStats of(Neo4jDependencies dependencies) {
        return new MemoryPoolsAdapter(dependencies::memoryPools);
    }

    static MemoryPoolStats of(MemoryPools pools) {
        return new MemoryPoolsAdapter(() -> pools);
    }

    @Override
    public List<Pool> globalPools() {
        return pools.get().getPools().stream().map(MemoryPoolsAdapter::pool).toList();
    }

    @Override
    public List<Pool> databasePools(String databaseName) {
        return pools.get().getPools().stream()
                .flatMap(global -> global.getDatabasePools().stream())
                .filter(pool -> databaseName.equals(pool.databaseName()))
                .map(MemoryPoolsAdapter::pool)
                .toList();
    }

    private static Pool pool(ScopedMemoryPool pool) {
        return new Pool() {
            private final String name = poolName(pool);

            @Override
            public String name() {
                return name;
            }

            @Override
            public long usedHeap() {
                return pool.usedHeap();
            }

            @Override
            public long usedNative() {
                return pool.usedNative();
            }

            @Override
            public long totalUsed() {
                return pool.totalUsed();
            }

            @Override
            public long totalSize() {
                return pool.totalSize();
            }

            @Override
            public long free() {
                return pool.free();
            }
        };
    }

    static String poolName(ScopedMemoryPool pool) {
        return pool.group().getName().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
