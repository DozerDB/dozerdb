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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_LABEL;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.DozerDbSettings;
import org.neo4j.dbms.api.DatabaseExistsException;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.Log;

/**
 * The {@code MultiDatabaseManager} class is responsible for managing the lifecycle of multiple databases
 * within the Neo4j instance. It offers methods for creating, starting, stopping, dropping, and listing databases.
 *
 * <p>Key Responsibilities:</p>
 * <ul>
 *   <li><strong>Creating a database:</strong> A synchronized method {@link #createDatabase(NamedDatabaseId)}
 *       creates a new database context and adds it to the database repository. It also checks if the
 *       database limit is exceeded.</li>
 *   <li><strong>Starting a database:</strong> Overloaded {@code startDatabase} methods start a database
 *       by either {@link NamedDatabaseId} or {@link StandaloneDatabaseContext}.</li>
 *   <li><strong>Stopping a database:</strong> Overloaded {@code stopDatabase} methods stop a database
 *       using different identifiers.</li>
 *   <li><strong>Dropping a database:</strong> {@link #dropDatabase(NamedDatabaseId)} stops, removes,
 *       and drops a database from the repository.</li>
 *   <li><strong>Listing databases:</strong> {@link #listAllNamedDatabaseIds(TopologyGraphDbmsModel.DatabaseStatus)}
 *       retrieves database IDs within a system database transaction, optionally filtering by status.</li>
 * </ul>
 *
 * <p>Dependencies:</p>
 * <ul>
 *   <li>{@link DatabaseRepository} for managing database contexts.</li>
 *   <li>{@link DatabaseContextFactory} for creating standalone database contexts.</li>
 *   <li>{@link GlobalModule} for logging and global dependencies like {@link DatabaseOperationCounts.Counter}.</li>
 * </ul>
 *
 * @see DatabaseRepository
 * @see StandaloneDatabaseContext
 */
public final class MultiDatabaseManager {

    private final DatabaseRepository<StandaloneDatabaseContext> databaseRepository;

    private final DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory;
    private final Log log;

    private final DatabaseOperationCounts.Counter counter;

    private final Config config;

    public MultiDatabaseManager(
            final GlobalModule globalModule,
            final DatabaseRepository<StandaloneDatabaseContext> databaseRepository,
            final DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory) {
        this.log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        this.databaseRepository = databaseRepository;
        this.databaseContextFactory = databaseContextFactory;
        this.counter = globalModule.getGlobalDependencies().resolveDependency(DatabaseOperationCounts.Counter.class);
        this.config = globalModule.getGlobalConfig();
    }

    /**
     * Creates a database with the specified name and registers it in the repository.
     * The database is in a started-ready state when initially created (not yet started).
     *
     * @param namedDatabaseId ID of database to create; must not be null
     * @return database context for the newly created database
     * @throws NullPointerException        if {@code namedDatabaseId} is null
     * @throws DatabaseExistsException     if a database with the specified name already exists
     * @throws DatabaseManagementException if the database limit has been exceeded
     */
    public synchronized StandaloneDatabaseContext createDatabase(final NamedDatabaseId namedDatabaseId) {
        requireNonNull(namedDatabaseId);
        log.info("Creating '%s'.", namedDatabaseId);
        checkDatabaseLimit(namedDatabaseId);
        final StandaloneDatabaseContext databaseContext =
                databaseContextFactory.create(namedDatabaseId, Optional.empty());
        databaseRepository.add(namedDatabaseId, databaseContext);
        return databaseContext;
    }

    /**
     * Drops a database by stopping it, removing it from the repository, and deleting its storage.
     * If the database does not exist, a warning is logged and the method returns without error.
     *
     * @param namedDatabaseId the ID of the database to drop
     * @throws DatabaseManagementException if stopping or dropping the database fails
     */
    public void dropDatabase(final NamedDatabaseId namedDatabaseId) {
        // --- Validation ---
        final Optional<StandaloneDatabaseContext> contextOptional =
                databaseRepository.getDatabaseContext(namedDatabaseId);

        if (contextOptional.isEmpty()) {
            log.warn("Database '%s' does not exist and cannot be dropped.", namedDatabaseId.name());
            return;
        }

        final StandaloneDatabaseContext context = contextOptional.get();

        // --- Stop ---
        try {
            log.info("Stopping database '%s' before dropping.", namedDatabaseId.name());
            this.stopDatabase(context);
        } catch (Exception e) {
            log.error(
                    "Failed to stop database '%s' before dropping. Error: %s", namedDatabaseId.name(), e.getMessage());
            throw new DatabaseManagementException("Failed to stop the database before dropping.", e);
        }

        // --- Drop ---
        try {
            log.info("Dropping database '%s'.", namedDatabaseId.name());
            databaseRepository.remove(namedDatabaseId);
            context.database().prepareToDrop();
            context.database().drop();
            log.info("Dropped database '%s' successfully.", namedDatabaseId.name());
        } catch (Exception e) {
            log.error("Failed to drop database '%s'. Error: %s", namedDatabaseId.name(), e.getMessage());
            throw new DatabaseManagementException("Failed to drop the database.", e);
        }
    }

    /**
     * Starts the database identified by the given {@link NamedDatabaseId}.
     * If starting fails, the error is logged but not propagated.
     *
     * @param namedDatabaseId the ID of the database to start
     * @throws DatabaseManagementException if the database context is not found in the repository
     */
    public void startDatabase(final NamedDatabaseId namedDatabaseId) {
        try {
            final Optional<StandaloneDatabaseContext> contextOptional =
                    databaseRepository.getDatabaseContext(namedDatabaseId);
            this.startDatabase(contextOptional.get());
        } catch (Exception e) {
            log.error("Failed to start '%s'.", namedDatabaseId, e);
        }
    }

    /**
     * Starts the database within the given context.
     * If starting fails, the context is marked as failed with an {@link UnableToStartDatabaseException}.
     *
     * @param context the database context to start
     */
    public void startDatabase(final StandaloneDatabaseContext context) {
        final var namedDatabaseId = context.database().getNamedDatabaseId();
        try {
            log.info("Starting '%s'.", namedDatabaseId);
            final Database database = context.database();
            database.start();
            this.counter.increaseStartCount();
        } catch (Exception e) {
            context.fail(UnableToStartDatabaseException.unableToStartDb(namedDatabaseId, e));
        }
    }

    /**
     * Stops the database within the given context. Clears any prior failure state before
     * attempting the stop so that earlier panics are not misinterpreted as shutdown failures.
     *
     * @param context the database context to stop
     */
    public void stopDatabase(final StandaloneDatabaseContext context) {
        final var namedDatabaseId = context.database().getNamedDatabaseId();
        // Make sure that any failure (typically database panic) that happened until now is not interpreted as shutdown
        // failure
        context.clearFailure();
        try {
            log.info("Stopping '%s'.", namedDatabaseId);
            final Database database = context.database();
            database.stop();
            log.info("Stopped '%s' successfully.", namedDatabaseId);
            this.counter.increaseStopCount();
        } catch (Exception e) {
            log.error("Failed to stop '%s'.", namedDatabaseId, e);
            context.fail(new DatabaseManagementException(
                    format("An error occurred! Unable to stop `%s`.", namedDatabaseId), e));
        }
    }

    /**
     * Stops the database identified by the given {@link NamedDatabaseId}.
     * If the database is not found, a warning is logged and the method returns without error.
     *
     * @param namedDatabaseId the ID of the database to stop
     */
    public void stopDatabase(final NamedDatabaseId namedDatabaseId) {
        final Optional<StandaloneDatabaseContext> contextOptional =
                databaseRepository.getDatabaseContext(namedDatabaseId);

        if (contextOptional.isEmpty()) {
            log.warn("Database '%s' does not exist and cannot be stopped.", namedDatabaseId.name());
            return;
        }

        final StandaloneDatabaseContext context = contextOptional.get();
        this.stopDatabase(context);
    }

    /**
     * Checks whether creating a new database would exceed the configured maximum.
     *
     * @param namedDatabaseId the ID of the database being created (used in the error message)
     * @throws DatabaseManagementException if the database limit has been reached
     */
    private void checkDatabaseLimit(final NamedDatabaseId namedDatabaseId) {
        final int maxDatabases =
                Optional.ofNullable(config.get(DozerDbSettings.max_databases)).orElse(100);
        if (databaseRepository.registeredDatabases().size() >= maxDatabases) {
            throw new DatabaseManagementException(format(
                    "Could not create database: %s because you have exceeded the limit of %d.",
                    namedDatabaseId.name(), maxDatabases));
        }
    }

    /**
     * Converts a given {@link Node} object into a {@link NamedDatabaseId}.
     *
     * <p>This method extracts the "name" and "uuid" properties from the provided Node,
     * using them to create a NamedDatabaseId object.</p>
     *
     * @param node the Node object containing the database properties
     * @return a NamedDatabaseId object created from the extracted name and UUID
     * @throws IllegalArgumentException if "name" or "uuid" properties are missing or if "uuid" is not a valid UUID
     */
    public NamedDatabaseId namedDatabaseIdFromNode(final Node node) {
        final String gdbName = (String) node.getProperty("name");
        if (gdbName == null) {
            throw new IllegalArgumentException("Node is missing required 'name' property.");
        }
        final String uuidString = (String) node.getProperty("uuid");
        if (uuidString == null) {
            throw new IllegalArgumentException("Node is missing required 'uuid' property.");
        }
        final UUID gdbUuid = UUID.fromString(uuidString);
        return DatabaseIdFactory.from(gdbName, gdbUuid);
    }

    /**
     * Retrieves a set of all named database IDs within the system.
     *
     * <p>This method begins a transaction with the system database and retrieves all nodes
     * with the {@code DATABASE_LABEL}. It then maps each node to a {@link NamedDatabaseId}
     * and collects them into a set.</p>
     *
     * <p>If any exceptions occur during this process, an error message is logged and
     * an empty set is returned.</p>
     *
     * @return a set of NamedDatabaseId objects representing all named database IDs, never null
     */
    public Set<NamedDatabaseId> listAllNamedDatabaseIds() {
        return listAllNamedDatabaseIds(null);
    }

    /**
     * Retrieves a set of named database IDs, optionally filtered by status.
     *
     * <p>Opens a transaction against the system database, finds all nodes with the
     * {@code DATABASE_LABEL}, and filters by the specified database status. If
     * {@code databaseStatus} is null, all databases are included.</p>
     *
     * @param databaseStatus the status to filter by, or null to include all databases
     * @return a set of matching {@link NamedDatabaseId} objects, never null
     */
    public Set<NamedDatabaseId> listAllNamedDatabaseIds(final TopologyGraphDbmsModel.DatabaseStatus databaseStatus) {

        try (var transaction = this.databaseRepository
                .getDatabaseContext(NAMED_SYSTEM_DATABASE_ID)
                .orElseThrow()
                .databaseFacade()
                .beginTx()) {

            final var result = transaction.findNodes(DATABASE_LABEL).stream()
                    .peek(node -> {
                        final String nodeStatus = (String) node.getProperty("status", "UNKNOWN");
                        final NamedDatabaseId dbId = namedDatabaseIdFromNode(node);
                        log.debug("Database Name: %s, Status: %s", dbId.name(), nodeStatus);
                    })
                    .filter(node -> {
                        if (databaseStatus == null) {
                            return true;
                        }
                        final String nodeStatus = (String) node.getProperty("status", "UNKNOWN");
                        return databaseStatus.statusName().equals(nodeStatus);
                    })
                    .map(this::namedDatabaseIdFromNode)
                    .collect(Collectors.toSet());

            log.debug("Returning Named Database IDs: %s", result);
            return result;

        } catch (Exception e) {
            log.error("An error occurred trying to list all the databases.", e);
            return Set.of();
        }
    }
}
