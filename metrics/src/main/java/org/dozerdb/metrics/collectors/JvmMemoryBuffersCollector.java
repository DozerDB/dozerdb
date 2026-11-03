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

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM memory buffers metrics (Operations Manual 5, "JVM memory buffers metrics"):
 * {@code vm.memory.buffer.<bufferpool>.count}, {@code .used} and {@code .capacity}, the estimated number of buffers,
 * bytes used and total capacity of each {@link BufferPoolMXBean} of the JVM ({@code direct}, {@code mapped} and,
 * on JDK 21, {@code mapped_non_volatile_memory}), global. {@code <bufferpool>} is the pool's MXBean name as
 * {@link JvmInstanceNames} spells it.
 */
public final class JvmMemoryBuffersCollector implements Collector {

    public static final String NAME = "vm.memory.buffer";

    private final List<BufferPoolMXBean> pools;

    public JvmMemoryBuffersCollector() {
        this.pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        for (BufferPoolMXBean pool : pools) {
            String prefix = "vm.memory.buffer." + JvmInstanceNames.of(pool.getName());
            metrics.gauge(prefix + ".count", pool::getCount);
            metrics.gauge(prefix + ".used", pool::getMemoryUsed);
            metrics.gauge(prefix + ".capacity", pool::getTotalCapacity);
        }
    }
}
