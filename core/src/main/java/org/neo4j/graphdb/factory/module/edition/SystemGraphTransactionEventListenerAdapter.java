/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.graphdb.factory.module.edition;

import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.util.List;
import java.util.Optional;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.MultiDatabaseManager;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListenerAdapter;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.InternalLog;

/**
 * Listens for transaction events on the system graph database to detect database lifecycle changes
 * (creation, status transitions, and deletion) and delegates to the {@link MultiDatabaseManager}.
 *
 * <p>This adapter handles three scenarios:</p>
 * <ul>
 *   <li><strong>Status change (beforeCommit):</strong> When a database node's {@code status} property changes
 *       between committed values, the adapter starts or stops the corresponding database.</li>
 *   <li><strong>Deletion (beforeCommit):</strong> When a node with the {@code DELETED_DATABASE_LABEL} is created,
 *       the adapter drops the corresponding database.</li>
 *   <li><strong>Creation (afterCommit):</strong> When a new database node is committed with a status and name,
 *       the adapter creates and starts the database.</li>
 * </ul>
 *
 * <p>System and default databases are always ignored by this adapter.</p>
 *
 * @see MultiDatabaseManager
 */
public class SystemGraphTransactionEventListenerAdapter extends TransactionEventListenerAdapter<Object> {

    private static final String PROPERTY_STATUS = "status";
    private static final String PROPERTY_NAME = "name";

    protected final Config config;
    private final MultiDatabaseManager databaseManager;
    private final List<String> gdbsToIgnore;

    private final InternalLog log;

    /**
     * Constructs the adapter with the given database manager and global module.
     *
     * @param multiDatabaseManager the manager for multi-database lifecycle operations
     * @param globalModule         the global module providing configuration and logging
     */
    public SystemGraphTransactionEventListenerAdapter(
            final MultiDatabaseManager multiDatabaseManager, final GlobalModule globalModule) {

        this.databaseManager = multiDatabaseManager;
        this.config = globalModule.getGlobalConfig();
        log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());

        final String defaultDatabaseName = config.get(initial_default_database);
        final String systemDatabaseName = NAMED_SYSTEM_DATABASE_ID.name();
        gdbsToIgnore = List.of(defaultDatabaseName, systemDatabaseName);
    }

    /**
     * Snapshot of property changes extracted from transaction data for a database node.
     *
     * @param newStatus the new value of the status property, or null if not changed
     * @param oldStatus the previous value of the status property, or null if not previously set
     * @param name      the database name, or null if not found
     */
    private record PropertySnapshot(String newStatus, String oldStatus, String name) {}

    /**
     * Extracts status and name property changes from the transaction data's assigned node properties.
     *
     * @param txData the transaction data containing property changes
     * @return a snapshot of the extracted property values
     */
    private PropertySnapshot extractPropertyChanges(final TransactionData txData) {
        String newStatus = null;
        String oldStatus = null;
        String name = null;

        for (final var entry : txData.assignedNodeProperties()) {
            if (PROPERTY_STATUS.equals(entry.key())) {
                newStatus = entry.value().toString();
                if (entry.previouslyCommittedValue() != null) {
                    oldStatus = entry.previouslyCommittedValue().toString();
                }
            }

            if (PROPERTY_NAME.equals(entry.key())) {
                name = entry.value().toString();
            }

            if (name == null) {
                try {
                    name = fetchDatabaseName(entry.entity());
                } catch (Exception e) {
                    log.debug("Exception trying to fetch database name: %s", e.getMessage());
                }
            }
        }

        return new PropertySnapshot(newStatus, oldStatus, name);
    }

    /**
     * Retrieves the {@link NamedDatabaseId} for a database with the specified name by searching
     * all registered databases.
     *
     * @param nameToFind the name of the database to find
     * @return an Optional containing the database ID if found, or empty if not found
     */
    public Optional<NamedDatabaseId> getNamedDatabaseIdForName(final String nameToFind) {
        return this.databaseManager.listAllNamedDatabaseIds().stream()
                .filter(namedDatabaseId -> namedDatabaseId.name().equals(nameToFind))
                .findFirst();
    }

    /**
     * Handles dropping databases (on deletion) and starting/stopping databases (on status change)
     * before the transaction is committed.
     *
     * @param txData          the changes that will be committed in this transaction
     * @param transaction     the ongoing transaction
     * @param databaseService the underlying database service
     * @return null (no state to pass to afterCommit)
     * @throws Exception if an error occurs during processing
     */
    @Override
    public Object beforeCommit(
            final TransactionData txData, final Transaction transaction, final GraphDatabaseService databaseService)
            throws Exception {

        // --- Extract property changes ---
        final PropertySnapshot snapshot = extractPropertyChanges(txData);
        String name = snapshot.name();

        // --- Handle status transitions ---
        if (snapshot.oldStatus() != null
                && snapshot.newStatus() != null
                && !snapshot.newStatus().equals(snapshot.oldStatus())
                && name != null) {
            final Optional<NamedDatabaseId> nId = getNamedDatabaseIdForName(name);
            if (nId.isPresent()) {
                handleStatusChange(snapshot.newStatus(), nId.get());
            } else {
                log.warn("Database '%s' was not found.", name);
            }
        }

        // --- Handle deletions ---
        boolean deleteAction = false;
        for (final Node node : txData.createdNodes()) {
            if (node.hasLabel(TopologyGraphDbmsModel.DELETED_DATABASE_LABEL)) {
                deleteAction = true;
                if (node.hasProperty(PROPERTY_NAME)) {
                    name = node.getProperty(PROPERTY_NAME).toString();
                }
            }
        }

        final boolean shouldProcess = (name != null && !gdbsToIgnore.contains(name) && deleteAction);

        if (shouldProcess) {
            final Optional<NamedDatabaseId> nId = getNamedDatabaseIdForName(name);
            if (nId.isPresent()) {
                log.info("Dropping database: '%s'.", name);
                databaseManager.dropDatabase(nId.get());
            } else {
                log.warn("Database '%s' was not found.", name);
            }
        }

        return super.beforeCommit(txData, transaction, databaseService);
    }

    /**
     * Handles creating and starting new databases after the transaction has been committed.
     * System and default databases are ignored.
     *
     * @param txData         the changes that were committed in this transaction
     * @param state          the object returned by {@link #beforeCommit}
     * @param systemDatabase the underlying database service
     */
    @Override
    public void afterCommit(
            final TransactionData txData, final Object state, final GraphDatabaseService systemDatabase) {

        final PropertySnapshot snapshot = extractPropertyChanges(txData);

        // We ignore / return if the transaction is related to the system or default graph database.
        if (snapshot.name() == null || gdbsToIgnore.contains(snapshot.name())) {
            return;
        }

        if (shouldCreateDatabase(snapshot.newStatus(), snapshot.name())) {
            final Optional<NamedDatabaseId> nId = getNamedDatabaseIdForName(snapshot.name());
            if (nId.isPresent()) {
                databaseManager.createDatabase(nId.get());
                databaseManager.startDatabase(nId.get());
                log.info("Created and started database: '%s'.", snapshot.name());
            } else {
                log.error("NamedDatabaseId for name '%s' was not found.", snapshot.name());
            }
        }
    }

    /**
     * Determines whether a database should be created based on the presence of a new status and name.
     *
     * @param newStatus the new status value from the transaction
     * @param name      the database name from the transaction
     * @return true if both newStatus and name are non-null, indicating a database creation event
     */
    private boolean shouldCreateDatabase(final String newStatus, final String name) {
        return (newStatus != null && name != null);
    }

    /**
     * Fetches the database name from the given node's "name" property.
     *
     * @param node the node to extract the name from
     * @return the database name, or null if the property is not present
     */
    private String fetchDatabaseName(final Node node) {
        if (node.hasProperty(PROPERTY_NAME)) {
            return node.getProperty(PROPERTY_NAME).toString();
        }
        return null;
    }

    /**
     * Handles a status change for a database by starting or stopping it as appropriate.
     *
     * @param newStatus       the new status value ("online" or "offline")
     * @param namedDatabaseId the ID of the database whose status changed
     */
    private void handleStatusChange(final String newStatus, final NamedDatabaseId namedDatabaseId) {
        switch (newStatus) {
            case "online" -> {
                log.info("Starting database: '%s'.", namedDatabaseId.name());
                databaseManager.startDatabase(namedDatabaseId);
            }
            case "offline" -> {
                log.info("Stopping database: '%s'.", namedDatabaseId.name());
                databaseManager.stopDatabase(namedDatabaseId);
            }
            default -> log.warn("Unknown status: '%s' for database: '%s'.", newStatus, namedDatabaseId.name());
        }
    }
}
