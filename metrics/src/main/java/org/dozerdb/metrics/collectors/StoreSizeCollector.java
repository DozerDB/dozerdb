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

import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.StoreSize;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database store size metrics (Operations Manual 5, "Database store size metrics"): the three
 * {@code store.size.*} gauges of one database, in bytes, read at report time from Neo4j's database size service.
 * Exactly the documented set is emitted: {@code total} (store files and transaction logs), {@code database} (store
 * files) and {@code available_reserved}. The Operations Manual 5 names the first {@code store.size.total}; a later
 * manual renames it {@code store.size.full}, which is not an Operations Manual 5 row and is not emitted here. The
 * adapter keeps the method name {@link StoreSize#full()} of the other release line so that a fix ports between the
 * lines with few edits; only the registered name follows this line's manual. One instance per started database.
 */
public final class StoreSizeCollector implements Collector {

    public static final String GROUP = "store.size";

    private final DatabaseDependencies database;

    public StoreSizeCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        StoreSize size = database.storeSize();
        metrics.gauge("store.size.total", size::full);
        metrics.gauge("store.size.database", size::database);
        metrics.gauge("store.size.available_reserved", size::availableReserved);
    }
}
