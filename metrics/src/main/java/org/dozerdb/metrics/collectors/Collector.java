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

import org.dozerdb.metrics.registry.MetricNames;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * A collector observes one Neo4j hook and keeps the metrics derived from it registered. A global collector is
 * built once per server; a database collector once per started database, from that database's dependencies, and
 * its metrics are retired when the database stops. The name identifies the collector in the log and lets the
 * registry retire all of its metrics at once.
 */
public interface Collector {

    String name();

    /** Registers this collector's metrics; called once, inside the isolation boundary. */
    void register(MetricRegistration metrics);

    /**
     * The name of a database collector, {@code database.<db>.<group>}: it mirrors the scope segment of the metrics
     * the collector registers, so that a log line about the collector names the database.
     */
    static String nameForDatabase(String group, String databaseName) {
        return MetricNames.DATABASE_SCOPE + "." + databaseName + "." + group;
    }
}
