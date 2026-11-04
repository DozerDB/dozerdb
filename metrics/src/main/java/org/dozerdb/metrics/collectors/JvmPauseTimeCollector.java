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

import java.util.function.LongConsumer;
import org.dozerdb.metrics.adapter.VmPauses;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * JVM pause time metrics (Operations Manual 2026.10, "JVM pause time metrics"): {@code vm.pause_time}, the
 * accumulated milliseconds of the VM pauses Neo4j's own pause monitor detects, global. Nothing in Neo4j keeps that
 * sum, so this is the one JVM metric fed per event: the collector listens to the pause monitor while registered and
 * adds each pause to an event counter. The pause monitor itself is Neo4j's ({@code internal.vm_pause_monitor.*}
 * settings); with it disabled the counter exists and stays at zero.
 */
public final class JvmPauseTimeCollector implements Collector {

    public static final String NAME = "vm.pause_time";

    private final VmPauses pauses;

    public JvmPauseTimeCollector(VmPauses pauses) {
        this.pauses = pauses;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        LongConsumer pauseTime = metrics.eventCounter("vm.pause_time");
        pauses.observePauses(pauseTime);
        metrics.onRetire(pauses::stopObservingPauses);
    }
}
