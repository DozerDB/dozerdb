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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.DozerDbSettings;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.*;
import org.neo4j.logging.internal.NullLogService;

/**
 * Unit tests for {@link MultiDatabaseManager}, verifying create, start, stop, and drop lifecycle operations.
 */
class MultiDatabaseManagerTest {

    private static final String TEST_DB_NAME = "testdb";

    private final Database mockDatabase = mock(Database.class);
    private final StandaloneDatabaseContext mockContext = mock(StandaloneDatabaseContext.class);
    private final NamedDatabaseId testDbId = DatabaseIdFactory.from(TEST_DB_NAME, UUID.randomUUID());

    private DatabaseRepository<StandaloneDatabaseContext> databaseRepository;
    private MultiDatabaseManager manager;

    @BeforeEach
    void setup() {
        final Config config = Config.defaults(DozerDbSettings.max_databases, 5);
        final var globalModule = mock(GlobalModule.class);

        when(globalModule.getLogService()).thenReturn(NullLogService.getInstance());
        when(globalModule.getGlobalConfig()).thenReturn(config);

        final Dependencies dependencies = new Dependencies();
        final DatabaseOperationCounts.Counter counter = new DatabaseOperationCounts.Counter();
        dependencies.satisfyDependency(counter);
        when(globalModule.getGlobalDependencies()).thenReturn(dependencies);

        databaseRepository = new DatabaseRepository<>(new MapCachingDatabaseIdRepository());

        // The factory returns our mock context for any database
        when(mockContext.database()).thenReturn(mockDatabase);
        when(mockDatabase.getNamedDatabaseId()).thenReturn(testDbId);

        manager = new MultiDatabaseManager(globalModule, databaseRepository, (namedDatabaseId, opts) -> {
            final StandaloneDatabaseContext ctx = mock(StandaloneDatabaseContext.class);
            final Database db = mock(Database.class);
            when(ctx.database()).thenReturn(db);
            when(db.getNamedDatabaseId()).thenReturn(namedDatabaseId);
            return ctx;
        });
    }

    @Test
    void createDatabase_shouldAddContextToRepository() {
        // when
        final StandaloneDatabaseContext result = manager.createDatabase(testDbId);

        // then
        assertThat(result).isNotNull();
        assertThat(databaseRepository.getDatabaseContext(testDbId)).isPresent();
    }

    @Test
    void createDatabase_shouldThrowWhenLimitExceeded() {
        // given - fill up to limit (5)
        for (int i = 0; i < 5; i++) {
            final NamedDatabaseId id = DatabaseIdFactory.from("db" + i, UUID.randomUUID());
            manager.createDatabase(id);
        }

        // when/then
        assertThatThrownBy(() -> manager.createDatabase(testDbId))
                .isInstanceOf(DatabaseManagementException.class)
                .hasMessageContaining("exceeded the limit");
    }

    @Test
    void createDatabase_shouldRejectNull() {
        assertThatThrownBy(() -> manager.createDatabase(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void startDatabase_byId_shouldStartDatabase() throws Exception {
        // given
        final StandaloneDatabaseContext ctx = manager.createDatabase(testDbId);

        // when
        manager.startDatabase(testDbId);

        // then
        verify(ctx.database()).start();
    }

    @Test
    void startDatabase_shouldHandleFailureGracefully() {
        // given - no context exists for this ID, so .get() will throw
        final NamedDatabaseId unknownId = DatabaseIdFactory.from("unknown", UUID.randomUUID());

        // when/then - should not propagate exception
        assertDoesNotThrow(() -> manager.startDatabase(unknownId));
    }

    @Test
    void stopDatabase_byId_shouldDelegate() throws Exception {
        // given
        final StandaloneDatabaseContext ctx = manager.createDatabase(testDbId);

        // when
        manager.stopDatabase(testDbId);

        // then
        verify(ctx.database()).stop();
    }

    @Test
    void stopDatabase_shouldWarnWhenNotFound() {
        // given
        final NamedDatabaseId unknownId = DatabaseIdFactory.from("unknown", UUID.randomUUID());

        // when/then - should not throw
        assertDoesNotThrow(() -> manager.stopDatabase(unknownId));
    }

    @Test
    void dropDatabase_shouldStopThenRemoveThenDrop() throws Exception {
        // given
        final StandaloneDatabaseContext ctx = manager.createDatabase(testDbId);
        final Database db = ctx.database();

        // when
        manager.dropDatabase(testDbId);

        // then - verify ordering: stop -> prepareToDrop -> drop
        final InOrder inOrder = inOrder(db);
        inOrder.verify(db).stop();
        inOrder.verify(db).prepareToDrop();
        inOrder.verify(db).drop();

        // and context should be removed from repository
        assertThat(databaseRepository.getDatabaseContext(testDbId)).isEmpty();
    }

    @Test
    void dropDatabase_shouldWarnWhenNotFound() {
        // given
        final NamedDatabaseId unknownId = DatabaseIdFactory.from("unknown", UUID.randomUUID());

        // when/then - should not throw
        assertDoesNotThrow(() -> manager.dropDatabase(unknownId));
    }

    @Test
    void namedDatabaseIdFromNode_shouldExtractCorrectly() {
        // given
        final String name = "mydb";
        final UUID uuid = UUID.randomUUID();
        final Node mockNode = mock(Node.class);
        when(mockNode.getProperty("name")).thenReturn(name);
        when(mockNode.getProperty("uuid")).thenReturn(uuid.toString());

        // when
        final NamedDatabaseId result = manager.namedDatabaseIdFromNode(mockNode);

        // then
        assertThat(result.name()).isEqualTo(name);
        assertThat(result.databaseId().uuid()).isEqualTo(uuid);
    }
}
