/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.procedures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * {@code dbms.setConfigValue} called over Bolt changes a dynamic setting of the running server, observed through
 * Community's own {@code dbms.listConfig}: what an administrator sees without a restart. A refused change leaves the
 * configuration as it was. Every test sets the value it needs first, so the tests run in any order on one server.
 */
class SetConfigValueIT {

    private static Neo4j neo4j;
    private static Driver driver;

    @BeforeAll
    static void startServer() {
        neo4j = ProcedureServers.startWithBolt();
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
    }

    @AfterAll
    static void stopServer() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void anAdminChangesADynamicSettingWithoutARestart() {
        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'VERBOSE')");
        assertThat(configValue("db.logs.query.enabled")).isEqualTo("VERBOSE");

        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')");

        assertThat(configValue("db.logs.query.enabled")).isEqualTo("INFO");
    }

    @Test
    void theChangeCanBeMadeFromTheSystemDatabase() {
        run("CALL dbms.setConfigValue('db.logs.query.threshold', '0s')");

        run("CALL dbms.setConfigValue('db.logs.query.threshold', '2s')", "system");

        assertThat(configValue("db.logs.query.threshold")).isEqualTo("2s");
    }

    @Test
    void anEmptyValueResetsTheSettingToItsDefault() {
        run("CALL dbms.setConfigValue('db.logs.query.threshold', '7s')");
        assertThat(configValue("db.logs.query.threshold")).isEqualTo("7s");

        run("CALL dbms.setConfigValue('db.logs.query.threshold', '')");

        assertThat(configValue("db.logs.query.threshold")).isEqualTo("0s");
    }

    @Test
    void aSettingThatIsNotDynamicIsRefusedAndUnchanged() {
        String before = configValue("server.directories.logs");

        assertThatThrownBy(() -> run("CALL dbms.setConfigValue('server.directories.logs', '/tmp/elsewhere')"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("'server.directories.logs' is not dynamic and can not be changed at runtime");

        assertThat(configValue("server.directories.logs")).isEqualTo(before);
    }

    @Test
    void anUnknownSettingIsRefused() {
        assertThatThrownBy(() -> run("CALL dbms.setConfigValue('db.logs.query.no_such_setting', 'INFO')"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("Unknown setting 'db.logs.query.no_such_setting'");
    }

    @Test
    void anInvalidValueIsRefusedAndTheSettingUnchanged() {
        run("CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')");

        assertThatThrownBy(() -> run("CALL dbms.setConfigValue('db.logs.query.enabled', 'LOUD')"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("Invalid value 'LOUD' for setting 'db.logs.query.enabled'")
                .hasMessageContaining("VERBOSE");

        assertThat(configValue("db.logs.query.enabled")).isEqualTo("INFO");
    }

    @Test
    void showProceduresListsItWithNeo4jsSignatureModeAndAdminMarking() {
        Record procedure;
        try (Session session = driver.session()) {
            procedure = session.run("SHOW PROCEDURES YIELD name, signature, mode, admin, worksOnSystem, description, "
                            + "argumentDescription WHERE name = 'dbms.setConfigValue' RETURN *")
                    .single();
        }

        assertThat(procedure.get("signature").asString())
                .isEqualTo("dbms.setConfigValue(setting :: STRING, value :: STRING)");
        assertThat(procedure.get("mode").asString()).isEqualTo("DBMS");
        assertThat(procedure.get("admin").asBoolean()).isTrue();
        assertThat(procedure.get("worksOnSystem").asBoolean()).isTrue();
        assertThat(procedure.get("description").asString())
                .startsWith("Update a given setting value. Passing an empty value results in removing the configured "
                        + "value and falling back to the default value.");
        List<String> arguments = procedure
                .get("argumentDescription")
                .asList(v -> v.get("name").asString() + " :: " + v.get("type").asString() + " - "
                        + v.get("description").asString());
        assertThat(arguments)
                .containsExactly("setting :: STRING - The name of the setting.", "value :: STRING - The value to set.");
    }

    static void run(String query) {
        run(query, "neo4j");
    }

    static void run(String query, String database) {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.run(query).consume();
        }
    }

    /** The live value of a setting as {@code dbms.listConfig} reports it. */
    static String configValue(String setting) {
        try (Session session = driver.session()) {
            return session.run(
                            "CALL dbms.listConfig($setting) YIELD name, value WHERE name = $setting RETURN value",
                            Map.of("setting", setting))
                    .single()
                    .get("value")
                    .asString();
        }
    }
}
