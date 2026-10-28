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

import static org.mockito.Mockito.*;
import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.MultiDatabaseManager;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.internal.NullLogService;

/**
 * Unit tests for {@link SystemGraphTransactionEventListenerAdapter}, verifying
 * status change handling, deletion handling, and database creation after commit.
 */
class SystemGraphTransactionEventListenerAdapterTest {

    private static final String TEST_DB_NAME = "testdb";
    private final NamedDatabaseId testDbId = DatabaseIdFactory.from(TEST_DB_NAME, UUID.randomUUID());

    private MultiDatabaseManager databaseManager;
    private SystemGraphTransactionEventListenerAdapter adapter;
    private TransactionData txData;
    private Transaction transaction;
    private GraphDatabaseService databaseService;

    @BeforeEach
    void setup() {
        databaseManager = mock(MultiDatabaseManager.class);
        final var globalModule = mock(GlobalModule.class);
        final Config config = Config.defaults(initial_default_database, "neo4j");

        when(globalModule.getLogService()).thenReturn(NullLogService.getInstance());
        when(globalModule.getGlobalConfig()).thenReturn(config);

        adapter = new SystemGraphTransactionEventListenerAdapter(databaseManager, globalModule);

        txData = mock(TransactionData.class);
        transaction = mock(Transaction.class);
        databaseService = mock(GraphDatabaseService.class);

        // Default: no assigned properties, no created nodes
        when(txData.assignedNodeProperties()).thenReturn(Collections.emptyList());
        when(txData.createdNodes()).thenReturn(Collections.emptyList());
    }

    @Test
    void handleStatusChange_online_shouldStartDatabase() throws Exception {
        // given - status changing from offline to online
        final var statusEntry = mockPropertyEntry("status", "online", "offline");
        final var nameEntry = mockPropertyEntry("name", TEST_DB_NAME, null);
        when(txData.assignedNodeProperties()).thenReturn(List.of(statusEntry, nameEntry));

        when(databaseManager.listAllNamedDatabaseIds()).thenReturn(Set.of(testDbId));

        // when
        adapter.beforeCommit(txData, transaction, databaseService);

        // then
        verify(databaseManager).startDatabase(testDbId);
        verify(databaseManager, never()).stopDatabase(any(NamedDatabaseId.class));
    }

    @Test
    void handleStatusChange_offline_shouldStopDatabase() throws Exception {
        // given - status changing from online to offline
        final var statusEntry = mockPropertyEntry("status", "offline", "online");
        final var nameEntry = mockPropertyEntry("name", TEST_DB_NAME, null);
        when(txData.assignedNodeProperties()).thenReturn(List.of(statusEntry, nameEntry));

        when(databaseManager.listAllNamedDatabaseIds()).thenReturn(Set.of(testDbId));

        // when
        adapter.beforeCommit(txData, transaction, databaseService);

        // then
        verify(databaseManager).stopDatabase(testDbId);
        verify(databaseManager, never()).startDatabase(any(NamedDatabaseId.class));
    }

    @Test
    void handleStatusChange_unknown_shouldNotStartOrStop() throws Exception {
        // given - status changing to unknown value
        final var statusEntry = mockPropertyEntry("status", "unknown_status", "online");
        final var nameEntry = mockPropertyEntry("name", TEST_DB_NAME, null);
        when(txData.assignedNodeProperties()).thenReturn(List.of(statusEntry, nameEntry));

        when(databaseManager.listAllNamedDatabaseIds()).thenReturn(Set.of(testDbId));

        // when
        adapter.beforeCommit(txData, transaction, databaseService);

        // then
        verify(databaseManager, never()).startDatabase(any(NamedDatabaseId.class));
        verify(databaseManager, never()).stopDatabase(any(NamedDatabaseId.class));
    }

    @Test
    void afterCommit_shouldIgnoreSystemDatabase() {
        // given - system database name
        final var statusEntry = mockPropertyEntry("status", "online", null);
        final var nameEntry = mockPropertyEntry("name", "system", null);
        when(txData.assignedNodeProperties()).thenReturn(List.of(statusEntry, nameEntry));

        // when
        adapter.afterCommit(txData, null, databaseService);

        // then
        verify(databaseManager, never()).createDatabase(any());
        verify(databaseManager, never()).startDatabase(any(NamedDatabaseId.class));
    }

    @Test
    void afterCommit_shouldIgnoreDefaultDatabase() {
        // given - default database name ("neo4j")
        final var statusEntry = mockPropertyEntry("status", "online", null);
        final var nameEntry = mockPropertyEntry("name", "neo4j", null);
        when(txData.assignedNodeProperties()).thenReturn(List.of(statusEntry, nameEntry));

        // when
        adapter.afterCommit(txData, null, databaseService);

        // then
        verify(databaseManager, never()).createDatabase(any());
        verify(databaseManager, never()).startDatabase(any(NamedDatabaseId.class));
    }

    @Test
    void beforeCommit_shouldDropOnDeletedLabel() throws Exception {
        // given - a node created with DELETED_DATABASE_LABEL
        final Node deletedNode = mock(Node.class);
        when(deletedNode.hasLabel(TopologyGraphDbmsModel.DELETED_DATABASE_LABEL))
                .thenReturn(true);
        when(deletedNode.hasProperty("name")).thenReturn(true);
        when(deletedNode.getProperty("name")).thenReturn(TEST_DB_NAME);

        when(txData.createdNodes()).thenReturn(List.of(deletedNode));
        when(databaseManager.listAllNamedDatabaseIds()).thenReturn(Set.of(testDbId));

        // when
        adapter.beforeCommit(txData, transaction, databaseService);

        // then
        verify(databaseManager).dropDatabase(testDbId);
    }

    /**
     * Creates a mock PropertyEntry for a node property.
     *
     * @param key            the property key
     * @param value          the new value
     * @param previousValue  the previous value (null if newly assigned)
     * @return the mocked PropertyEntry
     */
    @SuppressWarnings("unchecked")
    private PropertyEntry<Node> mockPropertyEntry(final String key, final Object value, final Object previousValue) {
        final PropertyEntry<Node> entry = mock(PropertyEntry.class);
        when(entry.key()).thenReturn(key);
        when(entry.value()).thenReturn(value);
        when(entry.previouslyCommittedValue()).thenReturn(previousValue);

        final Node entity = mock(Node.class);
        when(entity.hasProperty("name")).thenReturn(false);
        when(entry.entity()).thenReturn(entity);

        return entry;
    }
}
