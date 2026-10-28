/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.neo4j.dbms.database;

import static org.neo4j.function.ThrowingAction.executeAll;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;
import static org.neo4j.kernel.database.NamedDatabaseId.SYSTEM_DATABASE_NAME;

import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.dbms.api.DatabaseNotFoundHelper;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

/**
 * Manages the lifecycle operations (creation, initialization, starting, and stopping) for multiple
 * databases within a Neo4j instance.
 *
 * <p>This service coordinates the startup and shutdown ordering of databases:</p>
 * <ol>
 *   <li>System database is created and started first via {@link SystemDatabaseStarter}.</li>
 *   <li>Default database is initialized and started via {@link DefaultDatabaseStarter}.</li>
 *   <li>All other databases are started (if ONLINE) or registered (if OFFLINE) via {@link AllDatabasesStarter}.</li>
 *   <li>On shutdown, all databases are stopped with the system database stopped last via {@link AllDatabaseStopper}.</li>
 * </ol>
 *
 * @see MultiDatabaseManager
 * @see DatabaseRepository
 */
public final class MultiDatabaseLifecycleService {
    private final DatabaseRepository<StandaloneDatabaseContext> databaseRepository;
    private final String defaultGdbName;

    private final Log log;

    private final MultiDatabaseManager multiDatabaseManager;

    /**
     * Constructs a new {@code MultiDatabaseLifecycleService}.
     *
     * @param multiDatabaseManager the manager responsible for create/start/stop/drop operations
     * @param databaseRepository   the repository holding all registered database contexts
     * @param defaultGdbName       the name of the default graph database
     * @param logProvider          the log provider for internal logging
     */
    public MultiDatabaseLifecycleService(
            final MultiDatabaseManager multiDatabaseManager,
            final DatabaseRepository<StandaloneDatabaseContext> databaseRepository,
            final String defaultGdbName,
            final LogProvider logProvider) {
        this.multiDatabaseManager = multiDatabaseManager;
        this.databaseRepository = databaseRepository;
        this.defaultGdbName = defaultGdbName;
        this.log = logProvider.getLog(getClass());
    }

    /**
     * Returns a lifecycle adapter that creates the system database on init and starts it on start.
     *
     * @return a lifecycle for the system database
     */
    public Lifecycle systemDatabaseStarter() {
        return new SystemDatabaseStarter();
    }

    /**
     * Returns a lifecycle adapter that initializes and starts the default database.
     *
     * @return a lifecycle for the default database
     */
    public Lifecycle defaultDatabaseStarter() {
        return new DefaultDatabaseStarter();
    }

    /**
     * Returns a lifecycle adapter that starts all non-system, non-default databases.
     *
     * @return a lifecycle for starting all other databases
     */
    public Lifecycle allDatabaseStarter() {
        return new AllDatabasesStarter();
    }

    /**
     * Returns a lifecycle adapter that stops all databases on shutdown, with the system database stopped last.
     *
     * @return a lifecycle for shutting down all databases
     */
    public Lifecycle allDatabaseShutdown() {
        return new AllDatabaseStopper();
    }

    /**
     * Retrieves the system database context from the repository.
     *
     * @return the system database context
     * @throws org.neo4j.dbms.api.DatabaseNotFoundException if the system database is not registered
     */
    private StandaloneDatabaseContext getSystemDatabaseContext() {
        return databaseRepository
                .getDatabaseContext(NAMED_SYSTEM_DATABASE_ID)
                .orElseThrow(() -> DatabaseNotFoundHelper.databaseNotFound(SYSTEM_DATABASE_NAME));
    }

    /**
     * Retrieves the default database context from the repository, if it exists.
     *
     * @return an Optional containing the default database context, or empty if not yet created
     */
    private java.util.Optional<StandaloneDatabaseContext> getDefaultDatabaseContext() {
        return databaseRepository.getDatabaseContext(defaultGdbName);
    }

    /**
     * Initializes and starts the default database. Called once during startup.
     *
     * @throws DatabaseManagementException if the default database context already exists
     * @throws org.neo4j.dbms.api.DatabaseNotFoundException if the default database ID is not found
     */
    private synchronized void initDefaultDatabase() {
        final var defaultDatabaseId = databaseRepository
                .databaseIdRepository()
                .getByName(defaultGdbName)
                .orElseThrow(() -> DatabaseNotFoundHelper.databaseNotFound(defaultGdbName));
        if (databaseRepository.getDatabaseContext(defaultDatabaseId).isPresent()) {
            throw new DatabaseManagementException(
                    "Default Graph Database Initialization failure. The databaseId with ID: " + defaultDatabaseId
                            + " already exists.");
        }
        final var context = multiDatabaseManager.createDatabase(defaultDatabaseId);
        multiDatabaseManager.startDatabase(context);
    }

    /**
     * Starts all non-system, non-default databases. ONLINE databases are created and started;
     * OFFLINE databases are registered in the repository but not started.
     *
     * <p>Note: In the future, this method should check whether a database is already started
     * before attempting to start it, similar to {@link #initDefaultDatabase()}.</p>
     */
    private synchronized void startOtherDatabases() {
        // --- Start ONLINE databases ---
        try {
            multiDatabaseManager
                    .listAllNamedDatabaseIds(TopologyGraphDbmsModel.DatabaseStatus.ONLINE)
                    .forEach(namedDatabaseId -> {
                        if (!namedDatabaseId.isSystemDatabase()
                                && !namedDatabaseId.name().equals(defaultGdbName)) {
                            final var context = multiDatabaseManager.createDatabase(namedDatabaseId);
                            multiDatabaseManager.startDatabase(context);
                        }
                    });
        } catch (Exception e) {
            log.error("An error occurred trying to start ONLINE databases.", e);
        }

        // --- Register OFFLINE databases ---
        try {
            multiDatabaseManager
                    .listAllNamedDatabaseIds(TopologyGraphDbmsModel.DatabaseStatus.OFFLINE)
                    .forEach(namedDatabaseId -> {
                        if (!namedDatabaseId.isSystemDatabase()
                                && !namedDatabaseId.name().equals(defaultGdbName)) {
                            multiDatabaseManager.createDatabase(namedDatabaseId);
                        }
                    });
        } catch (Exception e) {
            log.error("An error occurred trying to register OFFLINE databases.", e);
        }
    }

    /**
     * Lifecycle adapter that creates the system database on {@code init} and starts it on {@code start}.
     */
    private class SystemDatabaseStarter extends LifecycleAdapter {
        @Override
        public void init() {
            multiDatabaseManager.createDatabase(NAMED_SYSTEM_DATABASE_ID);
        }

        @Override
        public void start() {
            multiDatabaseManager.startDatabase(getSystemDatabaseContext());
        }
    }

    /**
     * Lifecycle adapter that stops all databases on shutdown.
     *
     * <p>Stops the default database first, then iterates over all remaining non-system,
     * non-default databases, and finally stops the system database last.</p>
     */
    private class AllDatabaseStopper extends LifecycleAdapter {
        @Override
        public void stop() throws Exception {
            final var standaloneDatabaseContext = getDefaultDatabaseContext();
            standaloneDatabaseContext.ifPresent(multiDatabaseManager::stopDatabase);

            final StandaloneDatabaseContext systemContext = getSystemDatabaseContext();
            multiDatabaseManager.stopDatabase(systemContext);

            // Stop all remaining databases except system and default (already stopped above)
            databaseRepository.registeredDatabases().forEach((namedDatabaseId, databaseContext) -> {
                if (!namedDatabaseId.isSystemDatabase()
                        && !namedDatabaseId.name().equals(defaultGdbName)) {
                    multiDatabaseManager.stopDatabase(databaseContext);
                }
            });

            executeAll(
                    () -> standaloneDatabaseContext.ifPresent(this::throwIfUnableToStop),
                    () -> throwIfUnableToStop(systemContext));
        }

        /**
         * Throws a {@link DatabaseManagementException} if the given context is in a failed state,
         * unless the failure was caused by an {@link UnableToStartDatabaseException} (in which case
         * a compounded stop-failure error would be misleading).
         *
         * @param ctx the database context to check
         * @throws DatabaseManagementException if the context is in a failed state
         */
        private void throwIfUnableToStop(final StandaloneDatabaseContext ctx) {
            if (!ctx.isFailed()) {
                return;
            }

            // If we have not been able to start the database instance, then
            // we do not want to add a compounded error due to not being able
            // to stop the database.
            if (ctx.failureCause() instanceof UnableToStartDatabaseException) {
                return;
            }

            throw new DatabaseManagementException(
                    "Failed to stop " + ctx.database().getNamedDatabaseId().name() + " database.", ctx.failureCause());
        }
    }

    /**
     * Lifecycle adapter that starts all non-system, non-default databases on {@code start}.
     */
    private class AllDatabasesStarter extends LifecycleAdapter {
        @Override
        public void start() {
            startOtherDatabases();
        }
    }

    /**
     * Lifecycle adapter that initializes and starts the default database on {@code start}.
     */
    private class DefaultDatabaseStarter extends LifecycleAdapter {
        @Override
        public void start() {
            initDefaultDatabase();
        }
    }
}
