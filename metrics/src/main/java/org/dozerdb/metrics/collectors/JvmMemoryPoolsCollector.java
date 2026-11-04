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
import java.lang.management.MemoryPoolMXBean;
import java.util.List;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM memory pools metrics (Operations Manual 2026.10, "JVM memory pools metrics"): {@code vm.memory.pool.<pool>},
 * the estimated bytes used by each {@link MemoryPoolMXBean} of the JVM (heap and non-heap alike), global. A pool
 * that is not valid when the collector registers has no usage to report and gets no gauge; {@code <pool>} is the
 * pool's MXBean name as {@link JvmInstanceNames} spells it.
 */
public final class JvmMemoryPoolsCollector implements Collector {

    public static final String NAME = "vm.memory.pool";

    private final List<MemoryPoolMXBean> pools;

    public JvmMemoryPoolsCollector() {
        this.pools = ManagementFactory.getMemoryPoolMXBeans();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        for (MemoryPoolMXBean pool : pools) {
            if (pool.isValid()) {
                metrics.gauge(
                        "vm.memory.pool." + JvmInstanceNames.of(pool.getName()),
                        () -> pool.getUsage().getUsed());
            }
        }
    }
}
