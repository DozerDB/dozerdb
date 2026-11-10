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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;

class AdministrationParametersIntegrationTest {
    private static Neo4j server;
    private static GraphDatabaseService system;

    @BeforeAll
    static void start() {
        server = DozerDbBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .build();
        system = server.databaseManagementService().database("system");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void databaseLifecycleAcceptsQuotedParameterNamesAndMixedCaseValues() {
        Map<String, Object> parameters = Map.of("release`database", "UpgradeParameters");
        run("CREATE DATABASE $`release``database`", parameters);
        run("STOP DATABASE $`release``database`", parameters);
        assertEquals("offline", status());
        run("START DATABASE $`release``database`", parameters);
        assertEquals("online", status());
        run("DROP DATABASE $`release``database`", parameters);
        assertFalse(server.databaseManagementService().listDatabases().contains("upgradeparameters"));
    }

    @Test
    void parameterizedDropCannotDeleteTheSystemDatabase() {
        assertThrows(
                QueryExecutionException.class,
                () -> run("DROP DATABASE $`release``database`", Map.of("release`database", "SyStEm")));
        assertTrue(server.databaseManagementService().listDatabases().contains("system"));
    }

    @Test
    void generatedLookingAndUnicodeParameterNamesRemainDistinct() {
        run(
                "CREATE USER $`__internal_username` SET PASSWORD $`密碼` CHANGE NOT REQUIRED",
                Map.of(
                        "__internal_username",
                        "namespaceprobe",
                        "密碼",
                        "namespace-password-123",
                        "dozerdbParameter_5bc678bc",
                        "unrelated-value"));
        try (Transaction tx = system.beginTx()) {
            var user = tx.findNode(Label.label("User"), "name", "namespaceprobe");
            assertEquals(false, user.getProperty("passwordChangeRequired"));
        }
        run("DROP USER namespaceprobe", Map.of());
    }

    @Test
    void userCommandsAcceptQuotedPasswordAndHomeDatabaseParameterNames() {
        Map<String, Object> parameters = Map.of(
                "release`password", "upgrade-password-123",
                "release`home", "neo4j");
        run(
                "CREATE USER upgradeparameters SET PASSWORD $`release``password` CHANGE NOT REQUIRED "
                        + "SET HOME DATABASE $`release``home`",
                parameters);
        parameters = Map.of("release`password", "changed-password-456", "release`home", "neo4j");
        run(
                "ALTER USER upgradeparameters SET PASSWORD $`release``password` CHANGE NOT REQUIRED "
                        + "SET HOME DATABASE $`release``home`",
                parameters);
        try (Transaction tx = system.beginTx()) {
            var user = tx.findNode(Label.label("User"), "name", "upgradeparameters");
            assertEquals("neo4j", user.getProperty("homeDatabase"));
            assertEquals(false, user.getProperty("passwordChangeRequired"));
            tx.commit();
        }
        run("DROP USER upgradeparameters", Map.of());
    }

    private static String status() {
        try (Transaction tx = system.beginTx()) {
            try (var result =
                    tx.execute("SHOW DATABASE upgradeparameters YIELD requestedStatus RETURN requestedStatus")) {
                return (String) result.next().get("requestedStatus");
            }
        }
    }

    private static void run(String query, Map<String, Object> parameters) {
        try (Transaction tx = system.beginTx()) {
            try (var result = tx.execute(query, parameters)) {
                while (result.hasNext()) {
                    result.next();
                }
            }
            tx.commit();
        }
    }
}
