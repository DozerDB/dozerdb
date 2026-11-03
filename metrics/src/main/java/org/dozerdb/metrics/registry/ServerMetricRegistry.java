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

import com.codahale.metrics.Counter;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The one metric registry of a server: a Dropwizard registry whose entries carry Neo4j's full metric names.
 * Collectors register through {@link #forCollector} (global metrics) or {@link #forDatabaseCollector} (one
 * database's metrics); exporters read {@link #dropwizard()}. A name the {@link MetricNameFilter} excludes is never
 * registered, so an excluded metric costs nothing and reaches no exporter.
 *
 * <p>Every gauge and counter is guarded: the first read that throws reports the collector to
 * the {@link ComponentFailureHandler}, disables it and retires all of its metrics; the server keeps serving. A
 * collector retired in order ({@link #retire}, as a database collector is when its database stops) may register
 * again later; a disabled one may not. Retiring a collector also runs the releases it registered with
 * {@link MetricRegistration#onRetire}, so a Neo4j hook it held is given back.
 */
public final class ServerMetricRegistry {

    private final MetricRegistry dropwizard = new MetricRegistry();
    private final String prefix;
    private final MetricNameFilter filter;
    private final ComponentFailureHandler onFailure;
    private final Map<String, Set<String>> namesByCollector = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> releasesByCollector = new ConcurrentHashMap<>();
    private final Set<String> disabledCollectors = ConcurrentHashMap.newKeySet();

    public ServerMetricRegistry(String prefix, MetricNameFilter filter, ComponentFailureHandler onFailure) {
        this.prefix = prefix;
        this.filter = filter;
        this.onFailure = onFailure;
    }

    /** The underlying Dropwizard registry, for exporters. */
    public MetricRegistry dropwizard() {
        return dropwizard;
    }

    /** The registration handle of one global collector, identified by its name; metrics are {@code <prefix>.dbms.<metric>}. */
    public MetricRegistration forCollector(String collector) {
        return new Registration(collector, metric -> MetricNames.global(prefix, metric));
    }

    /**
     * The registration handle of one database collector, identified by its name; metrics are
     * {@code <prefix>.database.<db>.<metric>}.
     */
    public MetricRegistration forDatabaseCollector(String collector, String databaseName) {
        return new Registration(collector, metric -> MetricNames.database(prefix, databaseName, metric));
    }

    /** Removes every metric the named collector registered and runs the releases it registered. */
    public void retire(String collector) {
        Set<String> names = namesByCollector.remove(collector);
        if (names != null) {
            names.forEach(dropwizard::remove);
        }
        List<Runnable> releases = releasesByCollector.remove(collector);
        if (releases != null) {
            releases.forEach(release -> release(collector, release));
        }
    }

    /** Removes every metric and runs every release. */
    public void clear() {
        dropwizard.removeMatching(MetricFilter.ALL);
        namesByCollector.clear();
        releasesByCollector.keySet().forEach(this::retire);
    }

    private void release(String collector, Runnable release) {
        try {
            release.run();
        } catch (Exception | LinkageError e) {
            onFailure.componentFailed(collector, e);
        }
    }

    private final class Registration implements MetricRegistration {

        private final String collector;
        private final UnaryOperator<String> fullName;

        private Registration(String collector, UnaryOperator<String> fullName) {
            this.collector = collector;
            this.fullName = fullName;
        }

        @Override
        public void gauge(String metric, LongSupplier value) {
            register(metric, guardedGauge(value::getAsLong));
        }

        @Override
        public void doubleGauge(String metric, DoubleSupplier value) {
            register(metric, guardedGauge(value::getAsDouble));
        }

        @Override
        public void counter(String metric, LongSupplier count) {
            register(metric, new GuardedCounter(count));
        }

        @Override
        public LongConsumer eventCounter(String metric) {
            Counter counter = new Counter();
            return register(metric, counter) ? counter::inc : amount -> {};
        }

        @Override
        public LongConsumer histogram(String metric) {
            Histogram histogram = new Histogram(new ExponentiallyDecayingReservoir());
            return register(metric, histogram) ? histogram::update : value -> {};
        }

        @Override
        public void onRetire(Runnable release) {
            releasesByCollector
                    .computeIfAbsent(collector, ignored -> new CopyOnWriteArrayList<>())
                    .add(release);
        }

        /** Registers the metric under its full name unless the collector is disabled or the filters exclude it. */
        private boolean register(String metric, Metric registered) {
            String name = fullName.apply(metric);
            if (disabledCollectors.contains(collector) || !filter.accepts(name)) {
                return false;
            }
            dropwizard.register(name, registered);
            namesByCollector
                    .computeIfAbsent(collector, ignored -> ConcurrentHashMap.newKeySet())
                    .add(name);
            return true;
        }

        private <T> Gauge<T> guardedGauge(Supplier<T> value) {
            return () -> {
                if (disabledCollectors.contains(collector)) {
                    return null;
                }
                try {
                    return value.get();
                } catch (Exception | LinkageError e) {
                    failedWhileReporting(e);
                    return null;
                }
            };
        }

        private void failedWhileReporting(Throwable cause) {
            if (disabledCollectors.add(collector)) {
                onFailure.componentFailed(collector, cause);
                // A metric is read on an exporter's thread, possibly inside a JMX attribute read of the very MBean
                // that retiring would unregister; the retirement therefore happens off that thread.
                CompletableFuture.runAsync(() -> retire(collector));
            }
        }

        /**
         * A Dropwizard counter whose count is Neo4j's own value, read at report time; nothing increments it. Once
         * its collector is disabled it keeps reporting the last count until it is retired.
         */
        private final class GuardedCounter extends Counter {

            private final LongSupplier count;
            private volatile long lastCount;

            private GuardedCounter(LongSupplier count) {
                this.count = count;
            }

            @Override
            public long getCount() {
                if (disabledCollectors.contains(collector)) {
                    return lastCount;
                }
                try {
                    lastCount = count.getAsLong();
                } catch (Exception | LinkageError e) {
                    failedWhileReporting(e);
                }
                return lastCount;
            }
        }
    }
}
