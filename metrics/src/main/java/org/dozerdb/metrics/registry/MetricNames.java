/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.registry;

/**
 * Neo4j's metric naming: a global metric is {@code <prefix>.dbms.<metric>}, a database metric is
 * {@code <prefix>.database.<db>.<metric>} (Operations Manual 2026.10, metrics reference). These full names are
 * what the filters match, what the JMX object names carry and what every exporter emits.
 */
public final class MetricNames {

    /** Scope segment of a global metric. */
    public static final String GLOBAL_SCOPE = "dbms";

    /** Scope segment preceding the database name of a database metric. */
    public static final String DATABASE_SCOPE = "database";

    private MetricNames() {}

    public static String global(String prefix, String metric) {
        return prefix + "." + GLOBAL_SCOPE + "." + metric;
    }

    public static String database(String prefix, String databaseName, String metric) {
        return prefix + "." + DATABASE_SCOPE + "." + databaseName + "." + metric;
    }
}
