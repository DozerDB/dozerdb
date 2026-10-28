/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.kernel.impl.constraints;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;

/**
 * Integration tests validating that DozerDb's enterprise constraint features
 * work correctly when bootstrapped via the in-process test harness.
 *
 * <p>Each test method creates a constraint, verifies that a violating statement
 * throws a {@link QueryExecutionException}, and that a valid statement succeeds.
 * Tests clean up their own data and constraints in {@code @AfterAll}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstraintIntegrationTest {

    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    /**
     * Bootstraps a DozerDb instance with the HTTP/HTTPS server disabled
     * so tests run entirely in-process.
     */
    @BeforeAll
    static void startNeo4j() {
        neo4j = DozerDbBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .build();
        db = neo4j.defaultDatabaseService();
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

    // --- 1) Node Property Existence: email ---

    /**
     * Verifies that a node property existence constraint on {@code email}
     * rejects nodes missing the property and accepts nodes that include it.
     */
    @Test
    @Order(1)
    void testNodePropertyExistence_email() {
        // --- Create constraint ---
        executeSuccessfully(
                "CREATE CONSTRAINT test_exists_email FOR (p:TestNodeExistsEmail) REQUIRE p.email IS NOT NULL");

        // --- SHOULD FAIL: missing email (existence violations throw at commit time) ---
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("CREATE (p:TestNodeExistsEmail {name: 'No Email'})"),
                "Expected constraint violation when email is missing");

        // --- SHOULD PASS: email provided ---
        assertDoesNotThrow(() -> executeSuccessfully(
                "CREATE (p:TestNodeExistsEmail {name: 'Has Email', email: 'has.email@example.com'})"));
    }

    // --- 2) Node Property Existence: createdAt ---

    /**
     * Verifies that a node property existence constraint on {@code createdAt}
     * rejects nodes missing the property and accepts nodes that include it.
     */
    @Test
    @Order(2)
    void testNodePropertyExistence_createdAt() {
        // --- Create constraint ---
        executeSuccessfully(
                "CREATE CONSTRAINT test_exists_createdAt FOR (p:TestNodeExistsCreatedAt) REQUIRE p.createdAt IS NOT NULL");

        // --- SHOULD FAIL: missing createdAt (existence violations throw at commit time) ---
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully(
                        "CREATE (p:TestNodeExistsCreatedAt {email: 'missing.createdAt@example.com'})"),
                "Expected constraint violation when createdAt is missing");

        // --- SHOULD PASS: createdAt provided ---
        assertDoesNotThrow(() -> executeSuccessfully(
                "CREATE (p:TestNodeExistsCreatedAt {email: 'ok.createdAt@example.com', createdAt: datetime()})"));
    }

    // --- 3) Relationship Property Existence: since ---

    /**
     * Verifies that a relationship property existence constraint on {@code since}
     * rejects relationships missing the property and accepts relationships that include it.
     */
    @Test
    @Order(3)
    void testRelationshipPropertyExistence() {
        // --- Create constraint ---
        executeSuccessfully(
                "CREATE CONSTRAINT test_rel_exists_since FOR ()-[r:TEST_REL_SINCE]-() REQUIRE r.since IS NOT NULL");

        // --- SHOULD FAIL: relationship missing since (existence violations throw at commit time) ---
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully(
                        "CREATE (:TestRelNode {id: 'A'})-[:TEST_REL_SINCE]->(:TestRelNode {id: 'B'})"),
                "Expected constraint violation when since is missing");

        // --- SHOULD PASS: since provided ---
        assertDoesNotThrow(() -> executeSuccessfully(
                "CREATE (:TestRelNode {id: 'C'})-[:TEST_REL_SINCE {since: date()}]->(:TestRelNode {id: 'D'})"));
    }

    // --- 4) Single Property Uniqueness: email ---

    /**
     * Verifies that a uniqueness constraint on {@code email} rejects
     * duplicate values and allows unique insertions.
     */
    @Test
    @Order(4)
    void testSinglePropertyUniqueness() {
        // --- Create constraint ---
        executeSuccessfully("CREATE CONSTRAINT test_unique_email FOR (p:TestUniqueEmail) REQUIRE p.email IS UNIQUE");

        // --- SHOULD PASS: first insert ---
        executeSuccessfully("CREATE (:TestUniqueEmail {email: 'unique@example.com', name: 'First'})");

        // --- SHOULD FAIL: duplicate email ---
        assertThrows(
                QueryExecutionException.class,
                () -> executeSuccessfully("CREATE (:TestUniqueEmail {email: 'unique@example.com', name: 'Duplicate'})"),
                "Expected constraint violation for duplicate email");
    }

    // --- 5) Composite Uniqueness: (name, tenantId) ---

    /**
     * Verifies that a composite uniqueness constraint on {@code (name, tenantId)}
     * allows the same name with different tenant IDs but rejects duplicate combinations.
     */
    @Test
    @Order(5)
    void testCompositeUniqueness() {
        // --- Create constraint ---
        executeSuccessfully(
                "CREATE CONSTRAINT test_unique_name_tenant FOR (p:TestCompositeUnique) REQUIRE (p.name, p.tenantId) IS UNIQUE");

        // --- SHOULD PASS: different tenantId ---
        executeSuccessfully("CREATE (:TestCompositeUnique {name: 'Alice', tenantId: 't1'})");
        executeSuccessfully("CREATE (:TestCompositeUnique {name: 'Alice', tenantId: 't2'})");

        // --- SHOULD FAIL: same name + same tenantId ---
        assertThrows(
                QueryExecutionException.class,
                () -> executeSuccessfully("CREATE (:TestCompositeUnique {name: 'Alice', tenantId: 't1'})"),
                "Expected constraint violation for duplicate (name, tenantId)");
    }

    // --- 6) Node Key: (externalId, tenantId) ---

    /**
     * Verifies that a node key constraint on {@code (externalId, tenantId)} enforces
     * both existence and uniqueness of the composite key properties.
     */
    @Test
    @Order(6)
    void testNodeKey() {
        // --- Create constraint ---
        executeSuccessfully(
                "CREATE CONSTRAINT test_nodekey_ext_tenant FOR (p:TestNodeKey) REQUIRE (p.externalId, p.tenantId) IS NODE KEY");

        // --- SHOULD FAIL: missing tenantId (node key existence violations throw at commit time) ---
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("CREATE (:TestNodeKey {externalId: 'ext-1'})"),
                "Expected constraint violation when tenantId is missing");

        // --- SHOULD PASS: both key properties provided ---
        executeSuccessfully("CREATE (:TestNodeKey {externalId: 'ext-1', tenantId: 't1', payload: 'ok'})");

        // --- SHOULD FAIL: duplicate key pair (uniqueness violations throw at execute time) ---
        assertThrows(
                QueryExecutionException.class,
                () -> executeSuccessfully(
                        "CREATE (:TestNodeKey {externalId: 'ext-1', tenantId: 't1', payload: 'duplicate'})"),
                "Expected constraint violation for duplicate node key");

        // --- SHOULD PASS: same externalId but different tenantId ---
        assertDoesNotThrow(() ->
                executeSuccessfully("CREATE (:TestNodeKey {externalId: 'ext-1', tenantId: 't2', payload: 'ok'})"));
    }

    // --- 7) Username Uniqueness ---

    /**
     * Verifies that a uniqueness constraint on {@code username} rejects
     * duplicate usernames and allows unique insertions.
     */
    @Test
    @Order(7)
    void testUsernameUniqueness() {
        // --- Create constraint ---
        executeSuccessfully("CREATE CONSTRAINT test_unique_username FOR (u:TestUser) REQUIRE u.username IS UNIQUE");

        // --- SHOULD PASS: first insert ---
        executeSuccessfully("CREATE (:TestUser {username: 'neo', email: 'neo@example.com'})");

        // --- SHOULD FAIL: duplicate username ---
        assertThrows(
                QueryExecutionException.class,
                () -> executeSuccessfully("CREATE (:TestUser {username: 'neo', email: 'neo2@example.com'})"),
                "Expected constraint violation for duplicate username");
    }

    // --- 8) Property type constraints on created and changed values ---

    /**
     * Verifies node and relationship property type constraints reject wrong types on creation,
     * on {@code SET} of an existing property, and when a label is added to a node whose existing
     * property has the wrong type. Changed values reach the transaction state visitor as added
     * values on Neo4j 2026.x, so this pins the enforcement contract.
     */
    @Test
    @Order(8)
    void testPropertyTypeConstraintsOnChangedValues() {
        executeSuccessfully("CREATE CONSTRAINT test_type_node_age FOR (p:TestTypedNode) REQUIRE p.age IS :: INTEGER");
        executeSuccessfully(
                "CREATE CONSTRAINT test_type_rel_weight FOR ()-[r:TEST_TYPED_REL]-() REQUIRE r.weight IS :: FLOAT");

        // --- SHOULD FAIL: wrong type on creation ---
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("CREATE (:TestTypedNode {id: 'bad', age: 'young'})"),
                "Expected type violation on creation");

        // --- SHOULD PASS: right type, then SHOULD FAIL: changed to the wrong type ---
        executeSuccessfully("CREATE (:TestTypedNode {id: 'n1', age: 41})");
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH (p:TestTypedNode {id: 'n1'}) SET p.age = 'forty-two'"),
                "Expected type violation when an existing property changes to the wrong type");
        assertDoesNotThrow(() -> executeSuccessfully("MATCH (p:TestTypedNode {id: 'n1'}) SET p.age = 42"));

        // --- SHOULD FAIL: label added to a node whose existing property has the wrong type ---
        executeSuccessfully("CREATE (:TestUntyped {id: 'u1', age: 'text'})");
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH (p:TestUntyped {id: 'u1'}) SET p:TestTypedNode"),
                "Expected type violation when the constrained label is added");

        // --- Relationship: SHOULD PASS, then SHOULD FAIL on change, then SHOULD PASS again ---
        executeSuccessfully(
                "CREATE (:TestRelNode {id: 'E'})-[:TEST_TYPED_REL {weight: 1.5}]->(:TestRelNode {id: 'F'})");
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH ()-[r:TEST_TYPED_REL]->() SET r.weight = 'heavy'"),
                "Expected type violation when a relationship property changes to the wrong type");
        assertDoesNotThrow(() -> executeSuccessfully("MATCH ()-[r:TEST_TYPED_REL]->() SET r.weight = 2.5"));
    }

    // --- 9) Existence constraints on changed values ---

    /**
     * Verifies existence constraints reject removing or nulling a required property on an
     * existing node or relationship, while changing it to another value is accepted.
     */
    @Test
    @Order(9)
    void testExistenceConstraintsOnChangedValues() {
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH (p:TestNodeExistsEmail {name: 'Has Email'}) SET p.email = null"),
                "Expected existence violation when the property is set to null");
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH (p:TestNodeExistsEmail {name: 'Has Email'}) REMOVE p.email"),
                "Expected existence violation when the property is removed");
        assertThrows(
                ConstraintViolationException.class,
                () -> executeSuccessfully("MATCH ()-[r:TEST_REL_SINCE]->() REMOVE r.since"),
                "Expected existence violation when the relationship property is removed");
        assertDoesNotThrow(() -> executeSuccessfully(
                "MATCH (p:TestNodeExistsEmail {name: 'Has Email'}) SET p.email = 'changed@example.com'"));
    }

    // --- Cleanup ---

    /**
     * Drops all test constraints and deletes all test nodes to leave
     * the database clean after the test run.
     */
    @Test
    @Order(100)
    void cleanupTestData() {
        // --- Drop constraints ---
        executeSuccessfully("DROP CONSTRAINT test_exists_email IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_exists_createdAt IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_rel_exists_since IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_unique_email IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_unique_name_tenant IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_nodekey_ext_tenant IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_unique_username IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_type_node_age IF EXISTS");
        executeSuccessfully("DROP CONSTRAINT test_type_rel_weight IF EXISTS");

        // --- Delete all test nodes ---
        executeSuccessfully("MATCH (n) WHERE any(l IN labels(n) WHERE l STARTS WITH 'Test') DETACH DELETE n");

        // --- Verify no test constraints remain ---
        try (final Transaction tx = db.beginTx()) {
            final var result = tx.execute("SHOW CONSTRAINTS");
            while (result.hasNext()) {
                final var row = result.next();
                final String name = (String) row.get("name");
                assertFalse(name.startsWith("test_"), "Test constraint should have been dropped: " + name);
            }
            tx.commit();
        }
    }

    // --- Helper ---

    /**
     * Executes a single Cypher statement within its own transaction.
     *
     * @param cypher the Cypher statement to execute
     */
    private static void executeSuccessfully(final String cypher) {
        try (final Transaction tx = db.beginTx()) {
            tx.execute(cypher);
            tx.commit();
        }
    }
}
