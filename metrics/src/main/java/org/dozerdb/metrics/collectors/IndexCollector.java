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
import org.dozerdb.metrics.adapter.IndexStats;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database index metrics (Operations Manual 2026.10, "Database index metrics"): the {@code index.<type>.queried} and
 * {@code index.<type>.populated} rows of one database, one pair per documented index type (fulltext, lookup, text,
 * range, point, vector). The values are Neo4j's own per-type counters, exposed as counters read at report time; no
 * index event does extra work. One instance per started database, built from that database's dependencies.
 */
public final class IndexCollector implements Collector {

    public static final String GROUP = "index";

    private final DatabaseDependencies database;

    public IndexCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        IndexStats stats = database.indexStats();
        for (IndexStats.IndexType type : IndexStats.IndexType.values()) {
            metrics.counter(GROUP + "." + type.segment() + ".queried", () -> stats.queried(type));
            metrics.counter(GROUP + "." + type.segment() + ".populated", () -> stats.populated(type));
        }
    }
}
