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

import org.dozerdb.metrics.collectors.Collector;

/**
 * How to build one global collector, named before the collector exists: the isolation boundary builds it under
 * {@code name}, so a collector that cannot be built (a Neo4j hook that fails to link or to resolve) is logged and
 * disabled under that name while every other collector starts.
 *
 * @param name the collector's name, the component name of its construction
 * @param builder builds the collector; called once, inside the boundary
 */
record GlobalCollectorFactory(String name, Builder builder) {

    Collector build() throws Exception {
        return builder.build();
    }

    @FunctionalInterface
    interface Builder {
        Collector build() throws Exception;
    }
}
