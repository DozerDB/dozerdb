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
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * A global collector that cannot even be built, as a collector whose Neo4j hook is missing after an upgrade fails
 * with a linkage error when it is constructed; injected by the isolation test.
 */
public final class FailingAtConstructionCollector implements Collector {

    public FailingAtConstructionCollector() {
        throw new NoClassDefFoundError("simulated missing Neo4j class while building a global collector");
    }

    @Override
    public String name() {
        return "test.failing_construction";
    }

    @Override
    public void register(MetricRegistration metrics) {}
}
