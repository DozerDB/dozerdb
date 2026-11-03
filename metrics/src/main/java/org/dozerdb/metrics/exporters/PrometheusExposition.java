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

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Writes a Dropwizard registry in the Prometheus text format (version 0.0.4) the way Neo4j's Prometheus endpoint
 * does, as public scrapes of Neo4j's endpoint and the community dashboards that query it show: one family per metric, named by the full metric name with
 * every character outside {@code [A-Za-z0-9:]} (and a leading digit) replaced by {@code _}, no added prefix and
 * no labels. A gauge is a {@code gauge}; a counter a {@code counter} with the {@code _total} suffix; a histogram
 * a {@code summary} with the quantiles 0.5, 0.75, 0.95, 0.98, 0.99 and 0.999 plus {@code _count} and no
 * {@code _sum}; a timer like a histogram with values in seconds; a meter a {@code counter} with {@code _total}.
 * The {@code # HELP} line is the Prometheus Dropwizard bridge's and carries the original dotted name, as the
 * Operations Manual promises.
 *
 * <p>The output equals the bridge's over the same registry ({@code PrometheusExpositionTest}) except for the one
 * mapping Neo4j does and the stock bridge does not: a Dropwizard {@code Counter} is exposed as a {@code counter}
 * with {@code _total}, not as a suffix-less gauge.
 */
public final class PrometheusExposition {

    /** The content type of text format 0.0.4, as the bridge's {@code TextFormat.CONTENT_TYPE_004}. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /** A timer's snapshot is in nanoseconds; the bridge multiplies by this factor to report seconds. */
    private static final double NANOSECONDS_TO_SECONDS = 1.0D / TimeUnit.SECONDS.toNanos(1L);

    private final MetricRegistry registry;

    public PrometheusExposition(MetricRegistry registry) {
        this.registry = registry;
    }

    /** The exposition of every metric registered now. */
    public String text() {
        StringBuilder out = new StringBuilder();
        try {
            write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a StringBuilder does not throw
        }
        return out.toString();
    }

    /** Writes the exposition of every metric registered now, families in name order. */
    public void write(Appendable out) throws IOException {
        for (Family family : families()) {
            family.write(out);
        }
    }

    /**
     * The Prometheus name of a metric: every character outside {@code [A-Za-z0-9:]}, and a digit in the first
     * position, becomes {@code _}; nothing is added. {@code neo4j.dbms.vm.heap.used} is
     * {@code neo4j_dbms_vm_heap_used}.
     */
    public static String sanitize(String name) {
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed =
                    c == ':' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (i > 0 && c >= '0' && c <= '9');
            sanitized.append(allowed ? c : '_');
        }
        return sanitized.toString();
    }

    /**
     * Families in the bridge's order of types; two names that sanitise alike merge into the first family, as the
     * bridge merges them. The result is sorted by family name.
     */
    private Collection<Family> families() {
        Map<String, Family> families = new TreeMap<>();
        registry.getGauges().forEach((name, gauge) -> gauge(name, gauge).ifPresent(family -> add(families, family)));
        registry.getCounters().forEach((name, counter) -> add(families, counter(name, counter, counter.getCount())));
        registry.getHistograms()
                .forEach((name, histogram) ->
                        add(families, summary(name, histogram, histogram.getSnapshot(), histogram.getCount(), 1.0)));
        registry.getTimers()
                .forEach((name, timer) -> add(
                        families, summary(name, timer, timer.getSnapshot(), timer.getCount(), NANOSECONDS_TO_SECONDS)));
        registry.getMeters().forEach((name, meter) -> add(families, counter(name, meter, meter.getCount())));
        return families.values();
    }

    /**
     * A histogram or timer: a {@code summary} family with the six quantiles the bridge reports, a {@code _count}
     * sample and no {@code _sum}. Values stay in the metric's unit; a timer's are scaled from nanoseconds to seconds.
     */
    private static Family summary(String name, Metric metric, Snapshot snapshot, long count, double factor) {
        String prometheusName = sanitize(name);
        List<Sample> samples = List.of(
                new Sample(prometheusName, "0.5", snapshot.getMedian() * factor),
                new Sample(prometheusName, "0.75", snapshot.get75thPercentile() * factor),
                new Sample(prometheusName, "0.95", snapshot.get95thPercentile() * factor),
                new Sample(prometheusName, "0.98", snapshot.get98thPercentile() * factor),
                new Sample(prometheusName, "0.99", snapshot.get99thPercentile() * factor),
                new Sample(prometheusName, "0.999", snapshot.get999thPercentile() * factor),
                new Sample(prometheusName + "_count", count));
        return new Family(prometheusName, "summary", help(name, metric), samples);
    }

    /**
     * A documented counter: a {@code counter} family whose one sample is {@code <name>_total}. The stock bridge
     * would make a Dropwizard {@link Counter} a suffix-less gauge; every Neo4j dashboard and capture shows
     * {@code _total}.
     */
    private static Family counter(String name, Metric metric, long count) {
        String prometheusName = sanitize(name);
        return new Family(
                prometheusName, "counter", help(name, metric), List.of(new Sample(prometheusName + "_total", count)));
    }

    private static void add(Map<String, Family> families, Family family) {
        families.merge(family.name(), family, (first, next) -> {
            List<Sample> samples = new ArrayList<>(first.samples());
            samples.addAll(next.samples());
            return new Family(first.name(), first.type(), first.help(), samples);
        });
    }

    /** A gauge whose value is neither a number nor a boolean (a disabled guarded gauge reads {@code null}) is skipped. */
    private static Optional<Family> gauge(String name, Gauge<?> gauge) {
        Object value = gauge.getValue();
        double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else if (value instanceof Boolean b) {
            number = b ? 1 : 0;
        } else {
            return Optional.empty();
        }
        String prometheusName = sanitize(name);
        return Optional.of(
                new Family(prometheusName, "gauge", help(name, gauge), List.of(new Sample(prometheusName, number))));
    }

    private static String help(String name, Metric metric) {
        return "Generated from Dropwizard metric import (metric=" + name + ", type="
                + metric.getClass().getName() + ")";
    }

    /** A Prometheus value as Go prints it: {@code +Inf}, {@code -Inf}, otherwise Java's shortest double. */
    private static String goString(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return Double.toString(value);
    }

    /**
     * One metric family: the {@code # HELP} and {@code # TYPE} lines and the samples. A counter family's name is the
     * bare name; {@code _total} is appended when it is written, as the text format does.
     */
    private record Family(String name, String type, String help, List<Sample> samples) {

        void write(Appendable out) throws IOException {
            String suffix = type.equals("counter") ? "_total" : "";
            out.append("# HELP ")
                    .append(name)
                    .append(suffix)
                    .append(' ')
                    .append(escapeHelp(help))
                    .append('\n');
            out.append("# TYPE ")
                    .append(name)
                    .append(suffix)
                    .append(' ')
                    .append(type)
                    .append('\n');
            for (Sample sample : samples) {
                sample.write(out);
            }
        }

        private static String escapeHelp(String help) {
            return help.replace("\\", "\\\\").replace("\n", "\\n");
        }
    }

    /** One sample line; the only label a family ever carries is a summary's {@code quantile}. */
    private record Sample(String name, String quantile, double value) {

        Sample(String name, double value) {
            this(name, null, value);
        }

        void write(Appendable out) throws IOException {
            out.append(name);
            if (quantile != null) {
                out.append("{quantile=\"").append(quantile).append("\",}");
            }
            out.append(' ').append(goString(value)).append('\n');
        }
    }
}
