/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.registry;

import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The handle through which one collector registers its metrics. A handle is bound to its collector and to one
 * scope: global, naming metrics {@code <prefix>.dbms.<metric>} ({@link ServerMetricRegistry#forCollector}), or
 * one database, naming them {@code <prefix>.database.<db>.<metric>}
 * ({@link ServerMetricRegistry#forDatabaseCollector}). The registry remembers which collector owns each metric
 * so the collector can be retired as a whole, and silently skips a metric the filters exclude, which then costs
 * nothing.
 *
 * <p>Gauges and counters read their value when an exporter reports, never per event, so metrics add no work to the threads that produce the events: a
 * documented counter whose value Neo4j already counts (a {@code LongAdder}, a transaction id) is exposed as a
 * Dropwizard counter whose {@code Count} is that value, so JMX shows {@code Count} and CSV writes {@code count}
 * while the thread producing the event does no extra work. Only a histogram is fed per event, through the consumer
 * it hands back, into Dropwizard's default reservoir.
 */
public interface MetricRegistration {

    /** A gauge whose value is read from {@code value} at report time. */
    void gauge(String metric, LongSupplier value);

    /** A gauge whose value is a ratio or another non-integral quantity, read from {@code value} at report time. */
    void doubleGauge(String metric, DoubleSupplier value);

    /** A counter whose count is an existing monotonic value (Neo4j's, or one a collector keeps), read from {@code count} at report time. */
    void counter(String metric, LongSupplier count);

    /**
     * A counter that events increment, through the returned consumer, by the amount of each event (one per event,
     * or the milliseconds of a detected pause): for a documented counter whose sum nothing in Neo4j keeps, so the
     * collector keeps it from the events of a monitor it listens to. The consumer of a metric the filters exclude
     * discards its amounts, so an excluded counter costs nothing.
     */
    LongConsumer eventCounter(String metric);

    /**
     * A histogram over Dropwizard's default reservoir, fed through the returned consumer. The consumer of a metric
     * the filters exclude discards its values, so an excluded histogram costs nothing.
     */
    LongConsumer histogram(String metric);

    /**
     * Runs {@code release} when the collector is retired, for whatever reason: a collector that took hold of a
     * Neo4j hook (a callback slot, a monitor listener) gives it back here.
     */
    void onRetire(Runnable release);
}
