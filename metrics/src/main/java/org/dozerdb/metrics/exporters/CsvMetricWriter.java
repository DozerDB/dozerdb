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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes one report of a registry in Dropwizard's CSV layout (metrics-core {@code CsvReporter} 4.2.40): one file
 * per metric named {@code <full metric name>.csv}, a header written once per live file, and one row per report
 * whose first column {@code t} is epoch seconds. Numbers are formatted for {@link Locale#US}, the files are UTF-8, and each file is opened, appended and closed per
 * row so no handle stays open between reports. Before a row is appended the file is handed to the
 * {@link CsvRotation}, which archives it once it has reached the rotation size.
 *
 * <p>Per-type columns after {@code t}:
 *
 * <ul>
 *   <li>gauge: {@code value} (the value's own string form, so a long prints as {@code 42});
 *   <li>counter: {@code count};
 *   <li>histogram: {@code count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999}.
 * </ul>
 *
 * These are the only types the registry holds, because a collector registers through {@code MetricRegistration},
 * which offers gauges, counters and histograms; a collector that needs Dropwizard's meter or timer adds that type
 * there and its columns ({@code CsvReporter}'s rates per second and durations in milliseconds) here, with a test. A
 * gauge without a value (a guarded gauge whose collector was disabled) writes no row.
 */
public final class CsvMetricWriter {

    private static final String GAUGE_HEADER = "value";
    private static final String COUNTER_HEADER = "count";
    private static final String HISTOGRAM_HEADER = "count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999";

    private static final String GAUGE_FORMAT = "%s";
    private static final String COUNTER_FORMAT = "%d";
    private static final String HISTOGRAM_FORMAT = "%d,%d,%f,%d,%f,%f,%f,%f,%f,%f,%f";

    private final Path directory;
    private final CsvRotation rotation;
    private final Clock clock;

    public CsvMetricWriter(Path directory, CsvRotation rotation, Clock clock) {
        this.directory = directory;
        this.rotation = rotation;
        this.clock = clock;
    }

    /** Appends one row to every registered metric's file. */
    public void write(MetricRegistry registry) throws IOException {
        long timestamp = TimeUnit.MILLISECONDS.toSeconds(clock.millis());
        for (Map.Entry<String, Gauge> entry : registry.getGauges().entrySet()) {
            Object value = entry.getValue().getValue();
            if (value != null) {
                row(timestamp, entry.getKey(), GAUGE_HEADER, GAUGE_FORMAT, value);
            }
        }
        for (Map.Entry<String, Counter> entry : registry.getCounters().entrySet()) {
            row(
                    timestamp,
                    entry.getKey(),
                    COUNTER_HEADER,
                    COUNTER_FORMAT,
                    entry.getValue().getCount());
        }
        for (Map.Entry<String, Histogram> entry : registry.getHistograms().entrySet()) {
            Histogram histogram = entry.getValue();
            Snapshot snapshot = histogram.getSnapshot();
            row(
                    timestamp,
                    entry.getKey(),
                    HISTOGRAM_HEADER,
                    HISTOGRAM_FORMAT,
                    histogram.getCount(),
                    snapshot.getMax(),
                    snapshot.getMean(),
                    snapshot.getMin(),
                    snapshot.getStdDev(),
                    snapshot.getMedian(),
                    snapshot.get75thPercentile(),
                    snapshot.get95thPercentile(),
                    snapshot.get98thPercentile(),
                    snapshot.get99thPercentile(),
                    snapshot.get999thPercentile());
        }
    }

    private void row(long timestamp, String name, String header, String format, Object... values) throws IOException {
        Path file = directory.resolve(fileName(name));
        rotation.rotateIfDue(file);
        boolean fresh = Files.notExists(file) || Files.size(file) == 0;
        try (Writer out = Files.newBufferedWriter(file, UTF_8, CREATE, APPEND)) {
            if (fresh) {
                out.write(String.format(Locale.US, "t,%s%n", header));
            }
            Object[] columns = new Object[values.length + 1];
            columns[0] = timestamp;
            System.arraycopy(values, 0, columns, 1, values.length);
            out.write(String.format(Locale.US, "%d," + format + "%n", columns));
        }
    }

    /** {@code <full metric name>.csv}, with the same slash sanitising as Dropwizard's file provider. */
    static String fileName(String metricName) {
        return metricName.replaceFirst("^/", "").replace('/', '.') + ".csv";
    }
}
