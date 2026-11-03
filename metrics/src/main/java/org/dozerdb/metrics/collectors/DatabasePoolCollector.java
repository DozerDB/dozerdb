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
import org.dozerdb.metrics.adapter.MemoryPoolStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database neo4j pools metrics (Operations Manual 5, "Database neo4j pools metrics"): the manual's template
 * {@code <prefix>.database.<db>.pool.<pool>.<db>.<field>}, database name twice, for every pool a database holds (its
 * transaction pool and its "other" pool), with the same five fields as the global pools. One instance per started
 * database, built from the global pools filtered by that database's name; the database's pools exist before its
 * start event fires.
 */
public final class DatabasePoolCollector implements Collector {

    public static final String GROUP = "pool";

    private final DatabaseDependencies database;

    public DatabasePoolCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        String databaseName = database.databaseName();
        for (MemoryPoolStats.Pool pool : database.memoryPools().databasePools(databaseName)) {
            GlobalPoolCollector.registerPool(metrics, GROUP + "." + pool.name() + "." + databaseName, pool);
        }
    }
}
