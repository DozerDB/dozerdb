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
import org.dozerdb.metrics.adapter.EntityCounts;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Database data count metrics and the deprecated database data metrics (Operations Manual 2026.10, "Database data
 * count metrics" and "Database data metrics"): the {@code neo4j.count.*} and {@code ids_in_use.*} rows of one
 * database, all gauges read at report time from the database's store entity counters. The count rows carry the
 * literal {@code neo4j.count} segment the manual's reference table and default filter spell, which outweigh the
 * one page that writes it without: {@code <prefix>.database.<db>.neo4j.count.node}. The four {@code ids_in_use}
 * rows are deprecated in the manual and still emitted, because DozerDB emits the whole documented set. One instance
 * per started database, built from that database's dependencies.
 */
public final class EntityCountCollector implements Collector {

    public static final String GROUP = "count";

    private final DatabaseDependencies database;

    public EntityCountCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        EntityCounts counts = database.entityCounts();
        metrics.gauge("neo4j.count.node", counts::nodes);
        metrics.gauge("neo4j.count.relationship", counts::relationships);
        metrics.gauge("neo4j.count.relationship_types", counts::relationshipTypes);
        metrics.gauge("ids_in_use.node", counts::nodeIdsInUse);
        metrics.gauge("ids_in_use.relationship", counts::relationshipIdsInUse);
        metrics.gauge("ids_in_use.relationship_type", counts::relationshipTypeIdsInUse);
        metrics.gauge("ids_in_use.property", counts::propertyIdsInUse);
    }
}
