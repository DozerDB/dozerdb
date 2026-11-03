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
import org.dozerdb.metrics.adapter.BoltMonitors;
import org.dozerdb.metrics.adapter.BoltMonitors.BoltDriverEvents;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Bolt driver metrics (Operations Manual 5, "Bolt Driver metrics"): the {@code bolt_driver.api.*} rows,
 * global, one per driver API a TELEMETRY message can name. Neo4j fires the events only with
 * {@code server.bolt.telemetry.enabled=true} and only for drivers that send telemetry; the counters are registered
 * regardless and stay at zero otherwise. Each event is one event counter increment on the Bolt worker thread
 * (the only work metrics add there); the listener is removed when the collector is retired.
 */
public final class BoltDriverCollector implements Collector {

    public static final String NAME = "bolt_driver";

    private final BoltMonitors monitors;

    public BoltDriverCollector(BoltMonitors monitors) {
        this.monitors = monitors;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        Counts counts = new Counts(
                metrics.eventCounter("bolt_driver.api.managed_transaction_function_calls"),
                metrics.eventCounter("bolt_driver.api.unmanaged_transaction_calls"),
                metrics.eventCounter("bolt_driver.api.implicit_transaction_calls"),
                metrics.eventCounter("bolt_driver.api.execute_calls"));
        BoltMonitors.Subscription subscription = monitors.onDriverEvents(counts);
        metrics.onRetire(subscription::release);
    }

    /** One event counter per driver API; each event is one increment. */
    private record Counts(LongConsumer managed, LongConsumer unmanaged, LongConsumer implicit, LongConsumer execute)
            implements BoltDriverEvents {

        @Override
        public void managedTransactionFunctionCalled() {
            managed.accept(1);
        }

        @Override
        public void unmanagedTransactionCalled() {
            unmanaged.accept(1);
        }

        @Override
        public void implicitTransactionCalled() {
            implicit.accept(1);
        }

        @Override
        public void executeCalled() {
            execute.accept(1);
        }
    }
}
