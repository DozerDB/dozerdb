/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.neo4j.dbms.DatabaseState;
import org.neo4j.dbms.DatabaseStateService;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseContextProvider;
import org.neo4j.dbms.database.UnableToStartDatabaseException;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DatabaseStatus;
import org.neo4j.graphdb.event.DatabaseEventContext;
import org.neo4j.graphdb.event.DatabaseEventListener;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.storageengine.api.StorageEngineFactory;

/**
 * Adapts Neo4j's {@link DatabaseManagementService} (public API, the source of database events),
 * {@link DatabaseContextProvider} (the registered databases with their dependency resolvers) and
 * {@link DatabaseStateService} (each registered database's operator state and failure, what {@code SHOW DATABASES}
 * reports; not generic at Neo4j 5.26.32, so it is named as a plain type here) to {@link Databases}. A database counts as started once its availability guard is fulfilled: that
 * happens inside the database's lifecycle start, after every dependency is satisfied and before the start event
 * fires, so a database listed as started can be resolved from, and one listed after the listener was added was
 * also announced.
 *
 * <p>The state counts are derived from the state service alone. Community's operator states are started, stopped
 * and unknown (no store copying or draining), so a database is hosted when it is started, which the state
 * describes as {@code online} (the enum itself lives in the server artifact, its description in the DBMS model). It has failed when its
 * context records a failure: a start or stop that threw, or a panic, until the next stop clears it. It desires to
 * be started when it is started or when its failure is the one a failed start records
 * ({@link UnableToStartDatabaseException}); the requested status in the system graph is not read, because a report
 * must not open a transaction on the system database.
 *
 * <p>Neo4j raises its public create event whenever it initialises a database: for a new database, but also for every
 * existing database a server starts and for every {@code START} after a {@code STOP}. The adapter therefore delivers
 * {@link Listener#databaseCreated} only when the initialised database has no store yet, which it asks the database's
 * own storage engine factory about while the event is raised (the store is created later, when the database starts).
 */
public final class DatabaseManagementAdapter implements Databases {

    /** The description of the started operator state, {@code online}, as {@code SHOW DATABASES} prints it. */
    private static final String STARTED = DatabaseStatus.ONLINE.statusName();

    private final DatabaseManagementService managementService;
    private final DatabaseContextProvider<?> contexts;
    private final DatabaseStateService states;
    private final Map<Listener, DatabaseEventListener> eventListeners = new ConcurrentHashMap<>();

    DatabaseManagementAdapter(
            DatabaseManagementService managementService,
            DatabaseContextProvider<?> contexts,
            DatabaseStateService states) {
        this.managementService = managementService;
        this.contexts = contexts;
        this.states = states;
    }

    public static Databases of(Neo4jDependencies dependencies) {
        return new DatabaseManagementAdapter(
                dependencies.databaseManagementService(),
                dependencies.databaseContextProvider(),
                dependencies.databaseStateService());
    }

    @Override
    public void addListener(Listener listener) {
        DatabaseEventListener eventListener = new EventListener(listener);
        if (eventListeners.putIfAbsent(listener, eventListener) == null) {
            managementService.registerDatabaseEventListener(eventListener);
        }
    }

    @Override
    public void removeListener(Listener listener) {
        DatabaseEventListener eventListener = eventListeners.remove(listener);
        if (eventListener != null) {
            managementService.unregisterDatabaseEventListener(eventListener);
        }
    }

    @Override
    public List<String> started() {
        return contexts.registeredDatabases().values().stream()
                .filter(DatabaseManagementAdapter::isStarted)
                .map(context -> context.database().getNamedDatabaseId().name())
                .toList();
    }

    @Override
    public Optional<DatabaseDependencies> dependencies(String databaseName) {
        return contexts.getDatabaseContext(databaseName)
                .filter(DatabaseManagementAdapter::isStarted)
                .map(context -> new DatabaseDependenciesAdapter(databaseName, context.dependencies()));
    }

    @Override
    public StateCounts stateCounts() {
        long hosted = 0;
        long failed = 0;
        long desiredStarted = 0;
        for (DatabaseState state : states.stateOfAllDatabases().values()) {
            boolean started = state.operatorState().description().equals(STARTED);
            if (started) {
                hosted++;
            }
            if (state.hasFailed()) {
                failed++;
            }
            if (started || state.failure().orElse(null) instanceof UnableToStartDatabaseException) {
                desiredStarted++;
            }
        }
        return new StateCounts(hosted, failed, desiredStarted);
    }

    private static boolean isStarted(DatabaseContext context) {
        return context.databaseFacade().isAvailable();
    }

    /**
     * Whether the database being initialised has no store yet, asked of its own storage engine factory, layout and
     * file system, which Neo4j satisfies in the database's dependencies before it raises the create event. An unknown
     * database counts as new.
     */
    private boolean isNew(String databaseName) {
        return contexts.getDatabaseContext(databaseName)
                .map(DatabaseContext::dependencies)
                .map(dependencies -> !dependencies
                        .resolveDependency(StorageEngineFactory.class)
                        .storageExists(
                                dependencies.resolveDependency(FileSystemAbstraction.class),
                                dependencies.resolveDependency(DatabaseLayout.class)))
                .orElse(true);
    }

    /** Delivers the public database events; a create event only when it creates the database's store. */
    private final class EventListener implements DatabaseEventListener {

        private final Listener listener;

        private EventListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void databaseCreate(DatabaseEventContext event) {
            if (isNew(event.getDatabaseName())) {
                listener.databaseCreated(event.getDatabaseName());
            }
        }

        @Override
        public void databaseStart(DatabaseEventContext event) {
            listener.databaseStarted(event.getDatabaseName());
        }

        @Override
        public void databaseShutdown(DatabaseEventContext event) {
            listener.databaseStopped(event.getDatabaseName());
        }

        @Override
        public void databaseDrop(DatabaseEventContext event) {
            listener.databaseDropped(event.getDatabaseName());
        }

        @Override
        public void databasePanic(DatabaseEventContext event) {
            listener.databasePanicked(event.getDatabaseName());
        }
    }
}
