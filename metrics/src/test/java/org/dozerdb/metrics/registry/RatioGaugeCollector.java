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

import org.dozerdb.metrics.collectors.Collector;

/** A collector registering one double gauge; injected through the internal {@code additional_collectors} seam. */
public final class RatioGaugeCollector implements Collector {

    public static final String NAME = "test.ratio";
    public static final double RATIO = 0.25;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        metrics.doubleGauge(NAME + ".value", () -> RATIO);
    }
}
