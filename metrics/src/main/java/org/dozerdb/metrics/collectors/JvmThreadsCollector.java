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
 * JVM threads metrics (Operations Manual 5, "JVM threads metrics"): {@code vm.threads}, the live platform threads,
 * daemon and non-daemon, read from the platform {@link ThreadMXBean}, global. That is the 5 reference's only row in
 * this section; {@code vm.threads.virtual}, which a later manual adds, has no row on this line and is not registered.
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
