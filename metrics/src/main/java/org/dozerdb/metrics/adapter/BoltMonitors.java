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

/**
 * The Bolt server's monitors as the Bolt collectors see them: the events Neo4j's Bolt server fires on the global
 * monitor bus for every connection, message and response, and for every driver telemetry message. A listener added
 * here receives the events from then on, on the Bolt worker or network thread that produces them, so a listener
 * does nothing but a lock-free counter increment or a reservoir update, to keep the work on those threads negligible; the returned handle
 * removes it again.
 */
public interface BoltMonitors {

    /** Delivers every connection, message and response event to {@code listener} until the handle is released. */
    Subscription onConnectionEvents(BoltConnectionEvents listener);

    /** Delivers every driver telemetry event to {@code listener} until the handle is released. */
    Subscription onDriverEvents(BoltDriverEvents listener);

    /** The handle of an added listener. */
    @FunctionalInterface
    interface Subscription {
        void release();
    }

    /**
     * The events of Neo4j's {@code BoltConnectionMetricsMonitor}, in the order a connection fires them: a
     * connection counts as opened once its protocol version is negotiated and as closed only if it was negotiated;
     * it is activated when a worker thread takes it up and waiting when the thread releases it, reporting how long
     * the thread was bound; every request message is received, then started after {@code queuedMillis} in the
     * queue, then completed after {@code processingMillis}, or failed if its processing threw. The response events
     * fire only when Neo4j's response metrics listener is installed ({@code internal.server.bolt.response_metrics}).
     */
    interface BoltConnectionEvents {

        void connectionOpened();

        void connectionActivated();

        void connectionWaiting();

        void workerThreadReleased(long boundMillis);

        void messageReceived();

        void messageProcessingStarted(long queuedMillis);

        void messageProcessingCompleted(long processingMillis);

        void messageProcessingFailed();

        void responseSuccess();

        void responseIgnored();

        void responseFailed();

        void connectionClosed();
    }

    /**
     * The events of Neo4j's {@code BoltDriverMetricsMonitor}: one per telemetry message a driver sends to name the
     * API it used. Neo4j creates this monitor only with {@code server.bolt.telemetry.enabled=true}; without it no
     * event fires.
     */
    interface BoltDriverEvents {

        void managedTransactionFunctionCalled();

        void unmanagedTransactionCalled();

        void implicitTransactionCalled();

        void executeCalled();
    }
}
