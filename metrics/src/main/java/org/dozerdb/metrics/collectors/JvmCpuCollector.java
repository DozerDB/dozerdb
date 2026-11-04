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
import java.lang.management.OperatingSystemMXBean;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * CPU usage metrics (Operations Manual 2026.10, "CPU usage metrics"): {@code vm.cpu_load.system}, the recent CPU
 * usage of the whole operating system, and {@code vm.cpu_load.process}, that of this JVM, both doubles in
 * {@code [0.0, 1.0]}, global. They are the {@link com.sun.management.OperatingSystemMXBean} loads of the platform
 * operating system bean, reported as the JDK reports them: the manual warns the metric "may be unavailable"
 * depending on the Java distribution, and the JDK says so with a negative value (and with its first sample, before
 * it has two measurements to compare). A platform bean without those attributes registers no CPU metric.
 */
public final class JvmCpuCollector implements Collector {

    public static final String NAME = "vm.cpu_load";

    private final OperatingSystemMXBean operatingSystem;

    public JvmCpuCollector() {
        this.operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean loads) {
            metrics.doubleGauge("vm.cpu_load.system", loads::getCpuLoad);
            metrics.doubleGauge("vm.cpu_load.process", loads::getProcessCpuLoad);
        }
    }
}
