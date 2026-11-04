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

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;
import org.dozerdb.metrics.adapter.BoltMonitors;
import org.dozerdb.metrics.adapter.BoltMonitors.BoltConnectionEvents;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Bolt metrics (Operations Manual 2026.10, "Bolt metrics"): the {@code bolt.*} rows, global. Neo4j's Bolt server
 * only fires events for them, so this collector keeps the counts itself: event counters incremented on the Bolt
 * thread that fires the event, the only work metrics add there, by one per message or response and by the milliseconds a timing
 * event carries; lock-free adders for the connection events, from which the running and idle gauges are derived; and
 * the worker thread bound time as the one histogram, fed per release. The listener is removed when the collector is
 * retired.
 */
public final class BoltCollector implements Collector {

    public static final String NAME = "bolt";

    private final BoltMonitors monitors;

    public BoltCollector(BoltMonitors monitors) {
        this.monitors = monitors;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        Counts counts = new Counts();
        metrics.counter("bolt.connections_opened", counts.opened::sum);
        metrics.counter("bolt.connections_closed", counts.closed::sum);
        metrics.gauge("bolt.connections_running", counts::running);
        metrics.gauge("bolt.connections_idle", counts::idle);
        counts.received = metrics.eventCounter("bolt.messages_received");
        counts.started = metrics.eventCounter("bolt.messages_started");
        counts.done = metrics.eventCounter("bolt.messages_done");
        counts.failed = metrics.eventCounter("bolt.messages_failed");
        counts.queueMillis = metrics.eventCounter("bolt.accumulated_queue_time");
        counts.processingMillis = metrics.eventCounter("bolt.accumulated_processing_time");
        counts.boundMillis = metrics.histogram("bolt.worker_thread_bound_time");
        counts.responsesSucceeded = metrics.eventCounter("bolt.response_success");
        counts.responsesIgnored = metrics.eventCounter("bolt.response_ignored");
        counts.responsesFailed = metrics.eventCounter("bolt.response_failed");
        BoltMonitors.Subscription subscription = monitors.onConnectionEvents(counts);
        metrics.onRetire(subscription::release);
    }

    /**
     * The event counts: the connection events that the two gauges are derived from are kept in adders; every other
     * row is an event counter or the histogram, fed by the amount the event carries.
     */
    private static final class Counts implements BoltConnectionEvents {

        private static final LongConsumer NONE = ignored -> {};

        private final LongAdder opened = new LongAdder();
        private final LongAdder closed = new LongAdder();
        private final LongAdder activated = new LongAdder();
        private final LongAdder waiting = new LongAdder();
        private LongConsumer received = NONE;
        private LongConsumer started = NONE;
        private LongConsumer done = NONE;
        private LongConsumer failed = NONE;
        private LongConsumer queueMillis = NONE;
        private LongConsumer processingMillis = NONE;
        private LongConsumer responsesSucceeded = NONE;
        private LongConsumer responsesIgnored = NONE;
        private LongConsumer responsesFailed = NONE;
        private LongConsumer boundMillis = NONE;

        @Override
        public void connectionOpened() {
            opened.increment();
        }

        /** Connections a worker thread is bound to: activated by a worker and not yet released. */
        long running() {
            return activated.sum() - waiting.sum();
        }

        /** Open connections no worker thread is bound to. */
        long idle() {
            return opened.sum() - closed.sum() - running();
        }

        @Override
        public void connectionActivated() {
            activated.increment();
        }

        @Override
        public void connectionWaiting() {
            waiting.increment();
        }

        @Override
        public void workerThreadReleased(long boundMillis) {
            this.boundMillis.accept(boundMillis);
        }

        @Override
        public void messageReceived() {
            received.accept(1);
        }

        @Override
        public void messageProcessingStarted(long queuedMillis) {
            started.accept(1);
            queueMillis.accept(queuedMillis);
        }

        /** Fires for every started message, after a failure too: done counts successful and failed messages alike. */
        @Override
        public void messageProcessingCompleted(long processingMillis) {
            done.accept(1);
            this.processingMillis.accept(processingMillis);
        }

        @Override
        public void messageProcessingFailed() {
            failed.accept(1);
        }

        @Override
        public void responseSuccess() {
            responsesSucceeded.accept(1);
        }

        @Override
        public void responseIgnored() {
            responsesIgnored.accept(1);
        }

        @Override
        public void responseFailed() {
            responsesFailed.accept(1);
        }

        @Override
        public void connectionClosed() {
            closed.increment();
        }
    }
}
