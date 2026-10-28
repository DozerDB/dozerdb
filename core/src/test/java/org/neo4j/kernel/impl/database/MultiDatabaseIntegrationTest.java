/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.kernel.impl.database;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;

/**
 * Integration tests validating that DozerDb's enterprise multi-database features
 * work correctly when bootstrapped via the in-process test harness.
 *
 * <p>Tests cover the full database lifecycle: creation, listing, isolation,
 * and dropping of user databases via Cypher commands against the system database.
 * Each test builds on the state established by prior tests, so execution order
 * is enforced with {@link org.junit.jupiter.api.MethodOrderer.OrderAnnotation}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiDatabaseIntegrationTest {

    private static Neo4j neo4j;
    private static GraphDatabaseService defaultDb;
    private static GraphDatabaseService systemDb;

    /**
     * Bootstraps a DozerDb instance with the HTTP/HTTPS server disabled
     * so tests run entirely in-process. Obtains handles to both the default
     * ({@code neo4j}) and {@code system} databases.
     */
    @BeforeAll
    static void startNeo4j() {
        neo4j = DozerDbBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .build();
        defaultDb = neo4j.defaultDatabaseService();
        systemDb = neo4j.databaseManagementService().database("system");
    }

    /**
     * Shuts down the in-process DozerDb instance after all tests complete.
     */
    @AfterAll
    static void stopNeo4j() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    // --- 1) Create Databases ---

    /**
     * Creates two user databases ({@code test1} and {@code test2}) via
     * {@code CREATE DATABASE} Cypher commands on the system database,
     * then polls until each is ready to accept queries.
     */
    @Test
    @Order(1)
    void testCreateDatabases() {
        // --- Create test1 and test2 via system DB ---
        executeOnSystemDb("CREATE DATABASE test1");
        executeOnSystemDb("CREATE DATABASE test2");

        // --- Wait for each database to become available ---
        final GraphDatabaseService test1 = waitForDatabase("test1", 10_000L);
        assertNotNull(test1, "test1 database should be available after creation");

        final GraphDatabaseService test2 = waitForDatabase("test2", 10_000L);
        assertNotNull(test2, "test2 database should be available after creation");
    }

    // --- 2) SHOW DATABASES lists all expected databases ---

    /**
     * Runs {@code SHOW DATABASES} against the system database and verifies
     * that {@code neo4j}, {@code system}, {@code test1}, and {@code test2}
     * are all present in the results.
     */
    @Test
    @Order(2)
    void testShowDatabasesListsAllExpected() {
        final Set<String> dbNames = new HashSet<>();

        try (final Transaction tx = systemDb.beginTx()) {
            final Result result = tx.execute("SHOW DATABASES");
            while (result.hasNext()) {
                final Map<String, Object> row = result.next();
                dbNames.add((String) row.get("name"));
            }
            tx.commit();
        }

        assertTrue(dbNames.contains("neo4j"), "SHOW DATABASES should list 'neo4j'");
        assertTrue(dbNames.contains("system"), "SHOW DATABASES should list 'system'");
        assertTrue(dbNames.contains("test1"), "SHOW DATABASES should list 'test1'");
        assertTrue(dbNames.contains("test2"), "SHOW DATABASES should list 'test2'");
    }

    // --- 3) Cross-validate via DatabaseManagementService.listDatabases() ---

    /**
     * Cross-validates the database list by calling the programmatic
     * {@code DatabaseManagementService.listDatabases()} API and verifying
     * that all expected databases appear.
     */
    @Test
    @Order(3)
    void testListDatabasesViaManagementService() {
        final List<String> databases = neo4j.databaseManagementService().listDatabases();

        assertTrue(databases.contains("neo4j"), "listDatabases() should include 'neo4j'");
        assertTrue(databases.contains("system"), "listDatabases() should include 'system'");
        assertTrue(databases.contains("test1"), "listDatabases() should include 'test1'");
        assertTrue(databases.contains("test2"), "listDatabases() should include 'test2'");
    }

    // --- 4) Database Isolation ---

    /**
     * Verifies that data written to one user database is not visible from
     * another. Creates a labeled node in {@code test1}, confirms it is absent
     * from {@code test2}, then creates a labeled node in {@code test2} and
     * confirms it is absent from {@code test1}.
     */
    @Test
    @Order(4)
    void testDatabaseIsolation() {
        final GraphDatabaseService test1 = neo4j.databaseManagementService().database("test1");
        final GraphDatabaseService test2 = neo4j.databaseManagementService().database("test2");

        // --- Create a node in test1 ---
        executeOn(test1, "CREATE (:IsolationTest {source: 'test1'})");

        // --- Verify the node is NOT in test2 ---
        final long test2Count = countNodes(test2, "IsolationTest");
        assertEquals(0, test2Count, "test2 should not contain IsolationTest nodes from test1");

        // --- Create a node in test2 ---
        executeOn(test2, "CREATE (:IsolationTest {source: 'test2'})");

        // --- Verify test1 still has exactly one node (its own) ---
        final long test1Count = countNodes(test1, "IsolationTest");
        assertEquals(1, test1Count, "test1 should contain exactly 1 IsolationTest node");

        // --- Verify test2 also has exactly one node (its own) ---
        final long test2CountAfter = countNodes(test2, "IsolationTest");
        assertEquals(1, test2CountAfter, "test2 should contain exactly 1 IsolationTest node");
    }

    // --- 5) Default database is independent ---

    /**
     * Verifies that the default {@code neo4j} database does not contain
     * any data written to the {@code test1} or {@code test2} databases.
     */
    @Test
    @Order(5)
    void testDefaultDatabaseIndependentFromTestDatabases() {
        final long defaultCount = countNodes(defaultDb, "IsolationTest");
        assertEquals(0, defaultCount, "Default neo4j DB should have no IsolationTest nodes");
    }

    // --- 6) Stop and start ---

    /**
     * Stops and restarts a user database and checks that {@code SHOW DATABASES} reports the
     * requested status DozerDB recorded in the system graph, not Community's fixed "online".
     */
    @Test
    @Order(6)
    void testStopAndStartUpdateRequestedStatus() {
        executeOnSystemDb("STOP DATABASE test1");
        assertStatus("test1", "offline", "offline");

        executeOnSystemDb("START DATABASE test1");
        waitForDatabase("test1", 30_000);
        assertStatus("test1", "online", "online");
    }

    // --- 7) Drop Databases ---

    /**
     * Drops both user databases via {@code DROP DATABASE ... IF EXISTS},
     * then verifies they no longer appear in {@code SHOW DATABASES} while
     * the {@code neo4j} and {@code system} databases remain intact.
     */
    @Test
    @Order(7)
    void testDropDatabases() {
        // --- Drop test1 and test2 ---
        executeOnSystemDb("DROP DATABASE test1 IF EXISTS");
        executeOnSystemDb("DROP DATABASE test2 IF EXISTS");

        // --- Collect remaining database names ---
        final Set<String> dbNames = new HashSet<>();
        try (final Transaction tx = systemDb.beginTx()) {
            final Result result = tx.execute("SHOW DATABASES");
            while (result.hasNext()) {
                final Map<String, Object> row = result.next();
                dbNames.add((String) row.get("name"));
            }
            tx.commit();
        }

        // --- Verify test databases are gone ---
        assertFalse(dbNames.contains("test1"), "test1 should no longer be listed after DROP");
        assertFalse(dbNames.contains("test2"), "test2 should no longer be listed after DROP");

        // --- Verify system databases remain ---
        assertTrue(dbNames.contains("neo4j"), "neo4j should still exist after DROP");
        assertTrue(dbNames.contains("system"), "system should still exist after DROP");
    }

    // --- Helper Methods ---

    /**
     * Polls {@code SHOW DATABASES} until the named database reports the expected requested and
     * current status, failing after a short timeout.
     */
    private static void assertStatus(final String dbName, final String requested, final String current) {
        final long deadline = System.currentTimeMillis() + 30_000;
        Map<String, Object> last = Map.of();
        while (System.currentTimeMillis() < deadline) {
            try (final Transaction tx = systemDb.beginTx()) {
                final Result result = tx.execute(
                        "SHOW DATABASES YIELD name, requestedStatus, currentStatus WHERE name = $name RETURN *",
                        Map.of("name", dbName));
                if (result.hasNext()) {
                    last = result.next();
                }
                tx.commit();
            }
            if (requested.equals(last.get("requestedStatus")) && current.equals(last.get("currentStatus"))) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        fail("Expected " + dbName + " requestedStatus=" + requested + " currentStatus=" + current + " but got " + last);
    }

    /**
     * Executes a single Cypher statement against the system database
     * within its own transaction. Used for administrative commands like
     * {@code CREATE DATABASE} and {@code DROP DATABASE}.
     *
     * @param cypher the Cypher statement to execute
     */
    private static void executeOnSystemDb(final String cypher) {
        try (final Transaction tx = systemDb.beginTx()) {
            tx.execute(cypher);
            tx.commit();
        }
    }

    /**
     * Executes a single Cypher statement against the specified database
     * within its own transaction.
     *
     * @param db     the target database service
     * @param cypher the Cypher statement to execute
     */
    private static void executeOn(final GraphDatabaseService db, final String cypher) {
        try (final Transaction tx = db.beginTx()) {
            tx.execute(cypher);
            tx.commit();
        }
    }

    /**
     * Polls until a newly-created database is ready to accept queries.
     * Attempts a simple {@code RETURN 1} query at 200ms intervals until
     * it succeeds or the timeout expires.
     *
     * @param dbName        the name of the database to wait for
     * @param timeoutMillis maximum time in milliseconds to wait
     * @return the {@link GraphDatabaseService} for the database, or {@code null} if the timeout expired
     */
    private static GraphDatabaseService waitForDatabase(final String dbName, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            try {
                final GraphDatabaseService db =
                        neo4j.databaseManagementService().database(dbName);
                try (final Transaction tx = db.beginTx()) {
                    tx.execute("RETURN 1");
                    tx.commit();
                }
                return db;
            } catch (final Exception e) {
                // --- Database not ready yet; wait and retry ---
                try {
                    Thread.sleep(200L);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Counts the number of nodes with the given label in the specified database.
     *
     * @param db    the target database service
     * @param label the node label to count
     * @return the number of nodes with the specified label
     */
    private static long countNodes(final GraphDatabaseService db, final String label) {
        try (final Transaction tx = db.beginTx()) {
            final Result result = tx.execute("MATCH (n:" + label + ") RETURN count(n) AS cnt");
            final long count = (long) result.next().get("cnt");
            tx.commit();
            return count;
        }
    }
}
