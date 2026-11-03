/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.dozerdb.metrics.adapter.BoltMonitorsAdapter;
import org.dozerdb.metrics.adapter.ConfigAdapter;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.DatabaseManagementAdapter;
import org.dozerdb.metrics.adapter.InternalLogAdapter;
import org.dozerdb.metrics.adapter.JobSchedulerAdapter;
import org.dozerdb.metrics.adapter.MemoryPoolsAdapter;
import org.dozerdb.metrics.adapter.MetricsLog;
import org.dozerdb.metrics.adapter.Neo4jDependencies;
import org.dozerdb.metrics.adapter.PageCacheStatsAdapter;
import org.dozerdb.metrics.adapter.QueryRoutingMonitorAdapter;
import org.dozerdb.metrics.adapter.VmPauseMonitorAdapter;
import org.dozerdb.metrics.adapter.WebContainerThreadInfoAdapter;
import org.dozerdb.metrics.collectors.BoltCollector;
import org.dozerdb.metrics.collectors.BoltDriverCollector;
import org.dozerdb.metrics.collectors.CheckpointCollector;
import org.dozerdb.metrics.collectors.Collector;
import org.dozerdb.metrics.collectors.CypherCollector;
import org.dozerdb.metrics.collectors.DatabaseCountsCollector;
import org.dozerdb.metrics.collectors.DatabasePoolCollector;
import org.dozerdb.metrics.collectors.EntityCountCollector;
import org.dozerdb.metrics.collectors.GlobalPoolCollector;
import org.dozerdb.metrics.collectors.IndexCollector;
import org.dozerdb.metrics.collectors.JvmFileDescriptorsCollector;
import org.dozerdb.metrics.collectors.JvmGcCollector;
import org.dozerdb.metrics.collectors.JvmHeapCollector;
import org.dozerdb.metrics.collectors.JvmMemoryBuffersCollector;
import org.dozerdb.metrics.collectors.JvmMemoryPoolsCollector;
import org.dozerdb.metrics.collectors.JvmPauseTimeCollector;
import org.dozerdb.metrics.collectors.JvmThreadsCollector;
import org.dozerdb.metrics.collectors.PageCacheCollector;
import org.dozerdb.metrics.collectors.QueryExecutionCollector;
import org.dozerdb.metrics.collectors.QueryRoutingCollector;
import org.dozerdb.metrics.collectors.ServerThreadsCollector;
import org.dozerdb.metrics.collectors.StoreSizeCollector;
import org.dozerdb.metrics.collectors.TransactionCollector;
import org.dozerdb.metrics.collectors.TransactionLogCollector;
import org.dozerdb.metrics.exporters.CsvExporter;
import org.dozerdb.metrics.exporters.Exporter;
import org.dozerdb.metrics.exporters.GraphiteExporter;
import org.dozerdb.metrics.exporters.JmxExporter;
import org.dozerdb.metrics.exporters.PrometheusExporter;
import org.dozerdb.metrics.registry.MetricNameFilter;
import org.dozerdb.metrics.registry.ServerMetricRegistry;
import org.dozerdb.metrics.settings.MetricsConfiguration;

/**
 * The lifecycle of the metrics module inside one server: built with lazy dependencies, does all of its work in
 * {@link #start()} and releases everything in {@link #stop()}. Neo4j starts and stops it through the upstream
 * adapter's {@code MetricsExtensionFactory}, so nothing here names a Neo4j type. Every collector and exporter starts inside the
 * {@link IsolationBoundary}; nothing thrown here reaches Neo4j's extension loader, which would stop the DBMS.
 */
public final class MetricsExtension {

    /** The component that follows database starts and stops; its failure leaves the global metrics running. */
    static final String DATABASE_METRICS = "database-metrics";

    private final Neo4jDependencies dependencies;
    private final List<Exporter> exporters = new ArrayList<>();
    private MetricsLog log;
    private IsolationBoundary boundary;
    private ServerMetricRegistry registry;
    private DatabaseMetricsLifecycle databaseMetrics;

    public MetricsExtension(Neo4jDependencies dependencies) {
        this.dependencies = dependencies;
    }

    /** Starts every collector and exporter the configuration enables; nothing thrown here reaches the caller. */
    public void start() {
        try {
            log = InternalLogAdapter.of(dependencies.logService(), MetricsExtension.class);
            MetricsConfiguration configuration = ConfigAdapter.read(dependencies.config());
            if (!configuration.enabled()) {
                log.info("Metrics are disabled (server.metrics.enabled=false)");
                return;
            }
            boundary = new IsolationBoundary(log);
            registry = new ServerMetricRegistry(
                    configuration.prefix(),
                    new MetricNameFilter(configuration.filter(), configuration.denyFilter()),
                    boundary);
            List<GlobalCollectorFactory> globalCollectors = new ArrayList<>(builtInGlobalCollectors());
            List<DatabaseCollectorFactory> databaseCollectors = new ArrayList<>(BUILT_IN_DATABASE_COLLECTORS);
            for (String className : configuration.additionalCollectors()) {
                boundary.run(className, () -> addAdditionalCollector(className, globalCollectors, databaseCollectors));
            }
            for (GlobalCollectorFactory collector : globalCollectors) {
                startCollector(collector);
            }
            startDatabaseMetrics(databaseCollectors);
            if (configuration.csv().enabled()) {
                startExporter(new CsvExporter(
                        registry.dropwizard(),
                        configuration.csv(),
                        JobSchedulerAdapter.csvWrites(dependencies.jobScheduler()),
                        boundary));
            }
            if (configuration.jmxEnabled()) {
                startExporter(new JmxExporter(
                        registry.dropwizard(), configuration.prefix(), ManagementFactory.getPlatformMBeanServer()));
            }
            if (configuration.prometheus().enabled()) {
                startExporter(new PrometheusExporter(registry.dropwizard(), configuration.prometheus()));
            }
            if (configuration.graphite().enabled()) {
                startExporter(new GraphiteExporter(
                        registry.dropwizard(),
                        configuration.graphite(),
                        JobSchedulerAdapter.graphiteWrites(dependencies.jobScheduler()),
                        boundary,
                        log));
            }
        } catch (Exception | LinkageError e) {
            // Only resolving the log service itself can leave log null; Neo4j registers it before any extension.
            if (log != null) {
                log.error("Metrics could not be started and are disabled; the server continues without them", e);
            }
        }
    }

    /** Stops the exporters and the database metrics and removes every metric. */
    public void stop() {
        if (databaseMetrics != null) {
            try {
                databaseMetrics.stop();
            } catch (Exception | LinkageError e) {
                log.error("Metrics component '" + DATABASE_METRICS + "' failed to stop", e);
            }
            databaseMetrics = null;
        }
        for (Exporter exporter : exporters) {
            try {
                exporter.stop();
            } catch (Exception | LinkageError e) {
                if (log != null) {
                    log.error("Metrics exporter '" + exporter.name() + "' failed to stop", e);
                }
            }
        }
        exporters.clear();
        if (registry != null) {
            registry.clear();
        }
    }

    /**
     * The built-in global collectors, each built inside the isolation boundary under its name, so that a collector
     * whose Neo4j hook fails to link (a {@link LinkageError} after an upgrade) or to resolve disables itself alone.
     * The factories are lambdas, never constructor references: a constructor reference resolves its class when the
     * list is created, outside the boundary, while a lambda body resolves it only when it runs.
     */
    private List<GlobalCollectorFactory> builtInGlobalCollectors() {
        return List.of(
                new GlobalCollectorFactory(JvmHeapCollector.NAME, () -> new JvmHeapCollector()),
                new GlobalCollectorFactory(
                        BoltCollector.NAME, () -> new BoltCollector(BoltMonitorsAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        BoltDriverCollector.NAME, () -> new BoltDriverCollector(BoltMonitorsAdapter.of(dependencies))),
                new GlobalCollectorFactory(JvmGcCollector.NAME, () -> new JvmGcCollector()),
                new GlobalCollectorFactory(JvmMemoryPoolsCollector.NAME, () -> new JvmMemoryPoolsCollector()),
                new GlobalCollectorFactory(JvmMemoryBuffersCollector.NAME, () -> new JvmMemoryBuffersCollector()),
                new GlobalCollectorFactory(JvmThreadsCollector.NAME, () -> new JvmThreadsCollector()),
                new GlobalCollectorFactory(JvmFileDescriptorsCollector.NAME, () -> new JvmFileDescriptorsCollector()),
                new GlobalCollectorFactory(
                        JvmPauseTimeCollector.NAME,
                        () -> new JvmPauseTimeCollector(VmPauseMonitorAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        QueryRoutingCollector.NAME,
                        () -> new QueryRoutingCollector(QueryRoutingMonitorAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        GlobalPoolCollector.NAME, () -> new GlobalPoolCollector(MemoryPoolsAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        ServerThreadsCollector.NAME,
                        () -> new ServerThreadsCollector(() -> WebContainerThreadInfoAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        PageCacheCollector.NAME,
                        () -> new PageCacheCollector(() -> PageCacheStatsAdapter.of(dependencies))),
                new GlobalCollectorFactory(
                        DatabaseCountsCollector.NAME,
                        () -> new DatabaseCountsCollector(() -> DatabaseManagementAdapter.of(dependencies))));
    }

    /** The collectors of one database, built from its dependencies when it starts; lambdas for the same reason. */
    private static final List<DatabaseCollectorFactory> BUILT_IN_DATABASE_COLLECTORS = List.of(
            new DatabaseCollectorFactory(TransactionCollector.GROUP, database -> new TransactionCollector(database)),
            new DatabaseCollectorFactory(EntityCountCollector.GROUP, database -> new EntityCountCollector(database)),
            new DatabaseCollectorFactory(StoreSizeCollector.GROUP, database -> new StoreSizeCollector(database)),
            new DatabaseCollectorFactory(CheckpointCollector.GROUP, database -> new CheckpointCollector(database)),
            new DatabaseCollectorFactory(
                    TransactionLogCollector.GROUP, database -> new TransactionLogCollector(database)),
            new DatabaseCollectorFactory(DatabasePoolCollector.GROUP, database -> new DatabasePoolCollector(database)),
            new DatabaseCollectorFactory(CypherCollector.GROUP, database -> new CypherCollector(database)),
            new DatabaseCollectorFactory(IndexCollector.GROUP, database -> new IndexCollector(database)),
            new DatabaseCollectorFactory(
                    QueryExecutionCollector.GROUP, database -> new QueryExecutionCollector(database)));

    /** Database metrics follow the database events; the system database may already be started. */
    private void startDatabaseMetrics(List<DatabaseCollectorFactory> collectors) {
        boundary.run(DATABASE_METRICS, () -> {
            DatabaseMetricsLifecycle lifecycle = new DatabaseMetricsLifecycle(
                    DatabaseManagementAdapter.of(dependencies), registry, boundary, List.copyOf(collectors));
            lifecycle.start();
            databaseMetrics = lifecycle;
        });
    }

    /**
     * Builds a global collector under the factory's name, then registers it under its own; either failure disables
     * that collector alone. A collector that registered some metrics before failing has them retired.
     */
    private void startCollector(GlobalCollectorFactory factory) {
        boundary.run(factory.name(), () -> {
            Collector collector = factory.build();
            String name = collector.name();
            if (!boundary.run(name, () -> collector.register(registry.forCollector(name)))) {
                registry.retire(name);
            }
        });
    }

    /**
     * Resolves one class named in {@code internal.dozerdb.metrics.additional_collectors} into a factory, without
     * initialising it before it is known to be a {@link Collector}: a public constructor taking
     * {@link DatabaseDependencies} makes a database collector, built for every started database under the group
     * {@code <class name>}; otherwise a public no-argument constructor makes a global collector named by its class.
     * Anything else (an unknown class, a class that is not a collector, no such constructor) fails here and is
     * logged once under the class name.
     */
    private static void addAdditionalCollector(
            String className,
            List<GlobalCollectorFactory> globalCollectors,
            List<DatabaseCollectorFactory> databaseCollectors)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(className, false, MetricsExtension.class.getClassLoader());
        if (!Collector.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " does not implement " + Collector.class.getName());
        }
        Class<? extends Collector> collectorType = type.asSubclass(Collector.class);
        Constructor<? extends Collector> perDatabase = publicConstructor(collectorType, DatabaseDependencies.class);
        if (perDatabase != null) {
            databaseCollectors.add(
                    new DatabaseCollectorFactory(className, database -> instantiate(perDatabase, database)));
        } else {
            Constructor<? extends Collector> global = collectorType.getConstructor();
            globalCollectors.add(new GlobalCollectorFactory(className, () -> instantiate(global)));
        }
    }

    private static Constructor<? extends Collector> publicConstructor(
            Class<? extends Collector> type, Class<?>... parameters) {
        try {
            return type.getConstructor(parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Invokes a constructor and rethrows what the constructor itself threw, so the log names the real cause. */
    private static Collector instantiate(Constructor<? extends Collector> constructor, Object... arguments)
            throws Exception {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private void startExporter(Exporter exporter) {
        if (boundary.run(exporter.name(), exporter::start)) {
            exporters.add(exporter);
        }
    }
}
