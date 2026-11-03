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

import java.io.IOException;
import java.io.UncheckedIOException;
import org.neo4j.kernel.api.database.DatabaseSizeService;
import org.neo4j.kernel.database.NamedDatabaseId;

/**
 * Adapts Neo4j's {@link DatabaseSizeService} (one per server, satisfied as a global dependency by
 * {@code DatabaseManagementServiceFactory} and reachable from a database's resolver through its parent) to
 * {@link StoreSize} for one database, identified by its {@link NamedDatabaseId}. The service's implementation
 * answers 0 for a database that is not a {@code Database} (every DozerDB database is one) and sums the file sizes
 * of the database and transaction log directories on every call. An IO failure is rethrown unchecked, so the
 * registry's guard disables the collector as it does for any failing gauge.
 */
final class StoreSizeAdapter implements StoreSize {

    private final DatabaseSizeService sizes;
    private final NamedDatabaseId databaseId;

    StoreSizeAdapter(DatabaseSizeService sizes, NamedDatabaseId databaseId) {
        this.sizes = sizes;
        this.databaseId = databaseId;
    }

    @Override
    public long full() {
        try {
            return sizes.getDatabaseTotalSize(databaseId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long database() {
        try {
            return sizes.getDatabaseDataSize(databaseId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long availableReserved() {
        return sizes.getDatabaseAvailableReservedSize(databaseId);
    }
}
