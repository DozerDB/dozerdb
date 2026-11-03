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

import org.neo4j.bolt.protocol.common.connection.BoltConnectionMetricsMonitor;
import org.neo4j.bolt.protocol.common.connection.BoltDriverMetricsMonitor;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.monitoring.Monitors;

/**
 * Adapts the global {@link Monitors} bus to {@link BoltMonitors}. The Bolt server creates its
 * {@link BoltConnectionMetricsMonitor} and, with telemetry enabled, its {@link BoltDriverMetricsMonitor} on the
 * global bus; the bus delivers each call to every listener implementing the monitor interface, whenever the listener
 * was added, so a listener added before or after the Bolt server starts receives the events alike. Listener
 * exceptions are caught and logged by the bus, never propagated to the Bolt thread. The bus is resolved when a
 * listener is added, inside the collector's isolation boundary, so a missing bus fails only that collector.
 */
public final class BoltMonitorsAdapter implements BoltMonitors {

    private final Neo4jDependencies dependencies;

    private BoltMonitorsAdapter(Neo4jDependencies dependencies) {
        this.dependencies = dependencies;
    }

    public static BoltMonitors of(Neo4jDependencies dependencies) {
        return new BoltMonitorsAdapter(dependencies);
    }

    @Override
    public Subscription onConnectionEvents(BoltConnectionEvents listener) {
        return add(new ConnectionMonitor(listener));
    }

    @Override
    public Subscription onDriverEvents(BoltDriverEvents listener) {
        return add(new DriverMonitor(listener));
    }

    private Subscription add(Object monitorListener) {
        Monitors monitors = dependencies.monitors();
        monitors.addMonitorListener(monitorListener);
        return () -> monitors.removeMonitorListener(monitorListener);
    }

    private record ConnectionMonitor(BoltConnectionEvents events) implements BoltConnectionMetricsMonitor {

        @Override
        public void connectionOpened() {
            events.connectionOpened();
        }

        @Override
        public void connectionActivated() {
            events.connectionActivated();
        }

        @Override
        public void connectionWaiting() {
            events.connectionWaiting();
        }

        @Override
        public void messageReceived() {
            events.messageReceived();
        }

        @Override
        public void messageProcessingStarted(long queueTime) {
            events.messageProcessingStarted(queueTime);
        }

        @Override
        public void messageProcessingCompleted(long processingTime) {
            events.messageProcessingCompleted(processingTime);
        }

        @Override
        public void messageProcessingFailed() {
            events.messageProcessingFailed();
        }

        @Override
        public void responseSuccess() {
            events.responseSuccess();
        }

        @Override
        public void responseIgnored() {
            events.responseIgnored();
        }

        @Override
        public void responseFailed(Status status) {
            events.responseFailed();
        }

        @Override
        public void workerThreadReleased(long boundMillis) {
            events.workerThreadReleased(boundMillis);
        }

        @Override
        public void connectionClosed() {
            events.connectionClosed();
        }
    }

    private record DriverMonitor(BoltDriverEvents events) implements BoltDriverMetricsMonitor {

        @Override
        public void managedTransactionFunctionsInterfaceCalled() {
            events.managedTransactionFunctionCalled();
        }

        @Override
        public void unmanagedTransactionInterfaceCalled() {
            events.unmanagedTransactionCalled();
        }

        @Override
        public void implicitTransactionInterfaceCalled() {
            events.implicitTransactionCalled();
        }

        @Override
        public void executeInterfaceCalled() {
            events.executeCalled();
        }
    }
}
