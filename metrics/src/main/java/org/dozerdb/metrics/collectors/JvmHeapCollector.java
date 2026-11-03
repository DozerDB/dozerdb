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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM heap metrics (Operations Manual 5, "JVM Heap metrics"): {@code vm.heap.committed},
 * {@code vm.heap.used} and {@code vm.heap.max} in bytes, global, read from the platform {@link MemoryMXBean}.
 */
public final class JvmHeapCollector implements Collector {

    public static final String NAME = "vm.heap";

    private final MemoryMXBean memory;

    public JvmHeapCollector() {
        this.memory = ManagementFactory.getMemoryMXBean();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        metrics.gauge("vm.heap.committed", () -> heap().getCommitted());
        metrics.gauge("vm.heap.used", () -> heap().getUsed());
        metrics.gauge("vm.heap.max", () -> heap().getMax());
    }

    private MemoryUsage heap() {
        return memory.getHeapMemoryUsage();
    }
}
