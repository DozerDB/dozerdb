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

/** A collector whose gauge fails every time it is read; injected by the isolation test. */
public final class FailingAtReadCollector implements Collector {

    public static final String NAME = "test.failing_read";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        metrics.gauge(NAME + ".value", () -> {
            throw new IllegalStateException("simulated failure while reading " + NAME);
        });
        metrics.gauge(NAME + ".sibling", () -> 2L);
    }
}
