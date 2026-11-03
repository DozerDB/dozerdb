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

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM file descriptor metrics (Operations Manual 5, "JVM file descriptor metrics"):
 * {@code vm.file.descriptors.count}, the open file descriptors of the process, and
 * {@code vm.file.descriptors.maximum}, the operating system's limit, global. Both come from the
 * {@link UnixOperatingSystemMXBean} attributes of the platform operating system bean; on a platform whose bean
 * lacks them (Windows) the two metrics are not registered and the collector logs nothing, since there is nothing to
 * count.
 */
public final class JvmFileDescriptorsCollector implements Collector {

    public static final String NAME = "vm.file.descriptors";

    private final OperatingSystemMXBean operatingSystem;

    public JvmFileDescriptorsCollector() {
        this.operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        if (operatingSystem instanceof UnixOperatingSystemMXBean unix) {
            metrics.gauge("vm.file.descriptors.count", unix::getOpenFileDescriptorCount);
            metrics.gauge("vm.file.descriptors.maximum", unix::getMaxFileDescriptorCount);
        }
    }
}
