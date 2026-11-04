/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import java.util.function.LongConsumer;
import org.neo4j.monitoring.Monitors;
import org.neo4j.monitoring.VmPauseMonitor;

/**
 * Adapts Neo4j's {@link VmPauseMonitor.Monitor} events on the global {@link Monitors} bus to {@link VmPauses}.
 * {@code VmPauseMonitorComponent} (global lifecycle) runs the pause monitor when
 * {@code internal.vm_pause_monitor.enabled} is true (the default) and announces each detected pause through a
 * monitor created on the global bus; a listener added to that bus receives {@code pauseDetected} with the pause's
 * {@code VmPauseInfo}, whose {@code getPauseTime()} is in milliseconds. The bus is resolved when the listener is
 * added, so a server without it fails only the collector asking.
 */
public final class VmPauseMonitorAdapter implements VmPauses {

    private final Neo4jDependencies dependencies;
    private volatile Listener listener;

    private VmPauseMonitorAdapter(Neo4jDependencies dependencies) {
        this.dependencies = dependencies;
    }

    public static VmPauses of(Neo4jDependencies dependencies) {
        return new VmPauseMonitorAdapter(dependencies);
    }

    @Override
    public void observePauses(LongConsumer pauseMillis) {
        Listener added = new Listener(pauseMillis);
        dependencies.monitors().addMonitorListener(added);
        listener = added;
    }

    @Override
    public void stopObservingPauses() {
        Listener removed = listener;
        if (removed != null) {
            listener = null;
            dependencies.monitors().removeMonitorListener(removed);
        }
    }

    private static final class Listener extends VmPauseMonitor.Monitor.Adapter {

        private final LongConsumer pauseMillis;

        private Listener(LongConsumer pauseMillis) {
            this.pauseMillis = pauseMillis;
        }

        @Override
        public void pauseDetected(VmPauseMonitor.VmPauseInfo info) {
            pauseMillis.accept(info.getPauseTime());
        }
    }
}
