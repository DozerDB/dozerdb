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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.Databases;
import org.dozerdb.metrics.collectors.Collector;
import org.dozerdb.metrics.registry.MetricNames;
import org.dozerdb.metrics.registry.ServerMetricRegistry;

/**
 * Creates a database's metrics when the database starts and retires them when it stops or is dropped: "database
 * metrics are only available during the lifetime of the database" (Operations Manual 2026.10). It is driven by the
 * server's database events from the global extension, never by a database-scoped extension, because the offline
 * tools load those too. Databases already started when the listener is added (the system database may be) are
 * registered right after it; a database both announced and listed is registered once, and a start event always
 * rebuilds the collectors, since it means a fresh database lifecycle with fresh dependencies.
 *
 * <p>Everything that touches Neo4j runs inside the isolation boundary: resolving a database's dependencies under the
 * component name {@code database.<db>} (a failure loses that database's metrics), and building and registering each
 * collector under {@code database.<db>.<group>} (a failure loses that collector alone).
 */
final class DatabaseMetricsLifecycle implements Databases.Listener {

    private final Databases databases;
    private final ServerMetricRegistry registry;
    private final IsolationBoundary boundary;
    private final List<DatabaseCollectorFactory> collectors;

    /** The names of the collectors registered for each started database. */
    private final ConcurrentMap<String, List<String>> registered = new ConcurrentHashMap<>();

    DatabaseMetricsLifecycle(
            Databases databases,
            ServerMetricRegistry registry,
            IsolationBoundary boundary,
            List<DatabaseCollectorFactory> collectors) {
        this.databases = databases;
        this.registry = registry;
        this.boundary = boundary;
        this.collectors = collectors;
    }

    void start() {
        databases.addListener(this);
        for (String database : databases.started()) {
            registered.computeIfAbsent(database, this::startCollectors);
        }
    }

    /** Stops following database events; the registry's own clearing retires the metrics. */
    void stop() {
        databases.removeListener(this);
    }

    @Override
    public void databaseStarted(String database) {
        registered.compute(database, (name, previous) -> {
            if (previous != null) {
                retire(previous);
            }
            return startCollectors(name);
        });
    }

    @Override
    public void databaseStopped(String database) {
        List<String> names = registered.remove(database);
        if (names != null) {
            retire(names);
        }
    }

    /** A started database is stopped before it is dropped; a stopped one has nothing left to retire. */
    @Override
    public void databaseDropped(String database) {
        databaseStopped(database);
    }

    /** The names of the collectors started for the database, or {@code null} when it is not started after all. */
    private List<String> startCollectors(String database) {
        List<DatabaseDependencies> resolved = new ArrayList<>(1);
        boundary.run(
                MetricNames.DATABASE_SCOPE + "." + database,
                () -> databases.dependencies(database).ifPresent(resolved::add));
        if (resolved.isEmpty()) {
            return null;
        }
        DatabaseDependencies dependencies = resolved.get(0);
        List<String> names = new ArrayList<>();
        for (DatabaseCollectorFactory factory : collectors) {
            String name = Collector.nameForDatabase(factory.group(), database);
            names.add(name);
            if (!boundary.run(
                    name, () -> factory.build(dependencies).register(registry.forDatabaseCollector(name, database)))) {
                registry.retire(name);
            }
        }
        return List.copyOf(names);
    }

    private void retire(List<String> names) {
        names.forEach(registry::retire);
    }
}
