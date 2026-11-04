/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.graphite.GraphiteSender;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.dozerdb.metrics.adapter.ExporterScheduler;
import org.dozerdb.metrics.adapter.ExporterScheduler.ScheduledReport;
import org.dozerdb.metrics.adapter.MetricsLog;
import org.dozerdb.metrics.registry.ComponentFailureHandler;
import org.dozerdb.metrics.settings.MetricsConfiguration;

/**
 * Publishes every registered metric to a Graphite server over the plaintext protocol: one TCP connection per
 * report to {@code server.metrics.graphite.server}, every {@code server.metrics.graphite.interval}, carrying lines
 * of the form {@code <name> <value> <epoch seconds>}. Dropwizard's {@link GraphiteReporter} formats the lines; the
 * report runs on the scheduler group Neo4j defines for Graphite metrics, never on Dropwizard's own thread and never
 * on a database thread.
 *
 * <p>Names are the registered full metric names as they are, the prefix once, as the manual defines every metric name: {@code neo4j.dbms.vm.heap.used}
 * carries the metric prefix once, because the prefix is already the leading segment of every registered name. A
 * gauge is one line named by the metric; a counter adds {@code .count}; a histogram {@code .count}, {@code .max},
 * {@code .mean}, {@code .min}, {@code .stddev} and {@code .p50} to {@code .p999}; a meter adds {@code .count} and
 * the rates per second ({@code .m1_rate}, {@code .m5_rate}, {@code .m15_rate}, {@code .mean_rate}); a timer both,
 * its durations in milliseconds. These are the reporter's conventions, not reimplemented here. A gauge without a
 * value (a guarded gauge whose collector was disabled) sends no line.
 *
 * <p>Reachability follows the reporter: the sender connects at the start of every
 * report and closes at its end, so a server that is down is retried at the next report and one that comes up
 * later receives the metrics from then on. The reporter itself only warns through slf4j; this exporter logs an
 * error through the server log once per outage, naming the address, and an info line when the server is reached
 * again, and it never disables itself for an unreachable server. Anything else that fails inside a report is a
 * component failure: reported once, the schedule cancelled, the exporter disabled, the server serving.
 *
 * <p>The reporter is never started, so it owns no thread, and never stopped, because
 * {@code ScheduledReporter.stop()} sends one final report that an unreachable server could stall at shutdown;
 * {@link #stop()} cancels the schedule instead.
 */
public final class GraphiteExporter implements Exporter {

    public static final String NAME = "graphite";

    private final MetricRegistry registry;
    private final String host;
    private final int port;
    private final Duration interval;
    private final ExporterScheduler scheduler;
    private final ComponentFailureHandler onFailure;
    private final MetricsLog log;
    private volatile GraphiteReporter reporter;
    private volatile FailureRecordingSender sender;
    private volatile ScheduledReport report;
    private volatile boolean disabled;
    private volatile boolean unreachable;

    public GraphiteExporter(
            MetricRegistry registry,
            MetricsConfiguration.Graphite settings,
            ExporterScheduler scheduler,
            ComponentFailureHandler onFailure,
            MetricsLog log) {
        this.registry = registry;
        this.host = settings.host();
        this.port = settings.port();
        this.interval = settings.interval();
        this.scheduler = scheduler;
        this.onFailure = onFailure;
        this.log = log;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() {
        sender = new FailureRecordingSender(new Graphite(host, port));
        reporter = GraphiteReporter.forRegistry(registry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(MetricFilter.ALL)
                .build(sender);
        report = scheduler.scheduleRecurring(this::report, interval);
    }

    @Override
    public void stop() {
        cancel();
    }

    private void report() {
        if (disabled) {
            return;
        }
        try {
            reporter.report();
            IOException failure = sender.takeFailure();
            if (failure != null) {
                unreachable(failure);
            } else {
                reached();
            }
        } catch (Exception | LinkageError e) {
            disabled = true;
            onFailure.componentFailed(NAME, e);
            cancel();
        }
    }

    private void unreachable(IOException cause) {
        if (!unreachable) {
            unreachable = true;
            log.error(
                    "Metrics exporter '" + NAME + "' cannot reach the Graphite server at " + address()
                            + "; the server continues without it and the exporter reconnects at the next report",
                    cause);
        }
    }

    private void reached() {
        if (unreachable) {
            unreachable = false;
            log.info("Metrics exporter '" + NAME + "' reached the Graphite server at " + address() + " again");
        }
    }

    private String address() {
        return host + ":" + port;
    }

    private void cancel() {
        ScheduledReport scheduled = report;
        if (scheduled != null) {
            report = null;
            scheduled.cancel();
        }
    }

    /**
     * Records the first I/O failure of a report. The reporter catches the sender's {@link IOException}, warns
     * through slf4j and closes the connection, so the exporter learns about an unreachable server only here.
     */
    private static final class FailureRecordingSender implements GraphiteSender {

        private final GraphiteSender delegate;
        private volatile IOException failure;

        private FailureRecordingSender(GraphiteSender delegate) {
            this.delegate = delegate;
        }

        /** The failure of the report just run, cleared for the next one; {@code null} when it succeeded. */
        IOException takeFailure() {
            IOException recorded = failure;
            failure = null;
            return recorded;
        }

        @Override
        public void connect() throws IOException {
            recording(delegate::connect);
        }

        @Override
        public void send(String name, String value, long timestamp) throws IOException {
            recording(() -> delegate.send(name, value, timestamp));
        }

        @Override
        public void flush() throws IOException {
            recording(delegate::flush);
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public int getFailures() {
            return delegate.getFailures();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** Runs one call of the delegate and records its I/O failure before passing it on to the reporter. */
        private void recording(SenderCall call) throws IOException {
            try {
                call.run();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
                throw e;
            }
        }

        @FunctionalInterface
        private interface SenderCall {
            void run() throws IOException;
        }
    }
}
