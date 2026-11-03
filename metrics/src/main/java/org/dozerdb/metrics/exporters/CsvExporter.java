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

import com.codahale.metrics.MetricRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.dozerdb.metrics.adapter.ExporterScheduler;
import org.dozerdb.metrics.adapter.ExporterScheduler.ScheduledReport;
import org.dozerdb.metrics.registry.ComponentFailureHandler;
import org.dozerdb.metrics.settings.MetricsConfiguration;

/**
 * Publishes every registered metric as CSV files in {@code server.directories.metrics}, one row per
 * {@code server.metrics.csv.interval}, through a {@link CsvMetricWriter}. The report runs on the scheduler group
 * Neo4j defines for CSV metrics, so gauges are read and files written off the database threads.
 *
 * <p>Isolation, so that a metrics failure never stops the server: a directory that cannot be created or written fails {@link #start()}, and a
 * write failure at runtime reports the exporter once to the {@link ComponentFailureHandler} and cancels the
 * report; either way the exporter is disabled and the server keeps serving.
 */
public final class CsvExporter implements Exporter {

    public static final String NAME = "csv";

    private final MetricRegistry registry;
    private final Path directory;
    private final Duration interval;
    private final CsvRotation rotation;
    private final ExporterScheduler scheduler;
    private final ComponentFailureHandler onFailure;
    private volatile CsvMetricWriter writer;
    private volatile ScheduledReport report;
    private volatile boolean disabled;

    public CsvExporter(
            MetricRegistry registry,
            MetricsConfiguration.Csv settings,
            ExporterScheduler scheduler,
            ComponentFailureHandler onFailure) {
        this.registry = registry;
        this.directory = settings.directory();
        this.interval = settings.interval();
        this.rotation = new CsvRotation(settings.rotationSize(), settings.keepNumber(), settings.compression());
        this.scheduler = scheduler;
        this.onFailure = onFailure;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isWritable(directory)) {
            throw new IOException("The metrics directory is not writable: " + directory);
        }
        writer = new CsvMetricWriter(directory, rotation, Clock.systemUTC());
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
            writer.write(registry);
        } catch (Exception | LinkageError e) {
            disabled = true;
            onFailure.componentFailed(NAME, e);
            cancel();
        }
    }

    private void cancel() {
        ScheduledReport scheduled = report;
        if (scheduled != null) {
            report = null;
            scheduled.cancel();
        }
    }
}
