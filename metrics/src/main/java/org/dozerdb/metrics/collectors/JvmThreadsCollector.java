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
import java.lang.management.ThreadMXBean;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM threads metrics (Operations Manual 2026.10, "JVM threads metrics"): {@code vm.threads}, the live platform
 * threads, daemon and non-daemon, read from the platform {@link ThreadMXBean}, global. The manual's second row,
 * {@code vm.threads.virtual}, is not registered: Java 21 exposes no count of live virtual threads (the
 * {@code ThreadMXBean} counts platform threads only and {@code jdk.management.VirtualThreadSchedulerMXBean} arrives
 * in JDK 24 and counts mounted threads), so the inventory keeps that row Unsupported with this reason.
 */
public final class JvmThreadsCollector implements Collector {

    public static final String NAME = "vm.threads";

    private final ThreadMXBean threads;

    public JvmThreadsCollector() {
        this.threads = ManagementFactory.getThreadMXBean();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        metrics.gauge("vm.threads", threads::getThreadCount);
    }
}
