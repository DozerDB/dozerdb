/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.collectors.Collector;

/**
 * How to build one collector of every started database, named before the collector exists: for database
 * {@code <db>} the isolation boundary builds and registers it under {@code database.<db>.<group>}
 * ({@link Collector#nameForDatabase}), so a collector that cannot be built for a database is logged and disabled
 * under that name while the database's other collectors start.
 *
 * @param group the collector's group, the last segment of its name
 * @param builder builds the collector from the database's dependencies; called inside the boundary
 */
record DatabaseCollectorFactory(String group, Builder builder) {

    Collector build(DatabaseDependencies database) throws Exception {
        return builder.build(database);
    }

    @FunctionalInterface
    interface Builder {
        Collector build(DatabaseDependencies database) throws Exception;
    }
}
