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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * GC metrics (Operations Manual 5, "GC metrics"): {@code vm.gc.time.<gc>} (accumulated collection time in
 * milliseconds) and {@code vm.gc.count.<gc>} (collections so far), one pair per {@link GarbageCollectorMXBean} of the
 * JVM, global. Both are documented counters whose values the JVM already accumulates, so they are counters read at
 * report time; {@code <gc>} is the collector's MXBean name as {@link JvmInstanceNames} spells it.
 */
public final class JvmGcCollector implements Collector {

    public static final String NAME = "vm.gc";

    private final List<GarbageCollectorMXBean> collectors;

    public JvmGcCollector() {
        this.collectors = ManagementFactory.getGarbageCollectorMXBeans();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        for (GarbageCollectorMXBean collector : collectors) {
            String gc = JvmInstanceNames.of(collector.getName());
            metrics.counter("vm.gc.time." + gc, collector::getCollectionTime);
            metrics.counter("vm.gc.count." + gc, collector::getCollectionCount);
        }
    }
}
