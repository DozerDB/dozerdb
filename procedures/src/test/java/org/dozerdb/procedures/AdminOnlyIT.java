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
import static org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo.EMBEDDED_CONNECTION;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.security.AuthorizationViolationException;
import org.neo4j.harness.Neo4j;
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog;
import org.neo4j.internal.kernel.api.security.AccessMode;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.kernel.api.security.PermissionState;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.kernel.api.security.StaticAccessMode;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.database.PrivilegeDatabaseReference;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/**
 * The procedure is admin-only: the kernel refuses a caller whose security context does not grant admin procedures,
 * before the procedure runs, and the configuration stays as it was. In Community's security model, which DozerDB
 * uses, every authenticated user holds full DBMS rights, so the refusal is exercised through the embedded API with a
 * security context that denies admin procedures the way a role-based context would for a user without the privilege.
 */
class AdminOnlyIT {

    private static Neo4j neo4j;
    private static GraphDatabaseAPI database;

    @BeforeAll
    static void startServer() {
        neo4j = ProcedureServers.start();
        database = (GraphDatabaseAPI) neo4j.databaseManagementService().database("neo4j");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aCallerWithoutAdminRightsIsRefusedAndTheSettingUnchanged() {
        Config config = database.getDependencyResolver().resolveDependency(Config.class);
        var before = config.get(GraphDatabaseSettings.log_queries);

        try (var tx = database.beginTransaction(KernelTransaction.Type.EXPLICIT, withoutAdminRights())) {
            assertThatThrownBy(() -> tx.execute("CALL dbms.setConfigValue('db.logs.query.enabled', 'OFF')")
                            .accept(row -> true))
                    .isInstanceOf(AuthorizationViolationException.class)
                    .hasMessageContaining("Executing admin procedure 'dbms.setConfigValue'")
                    .hasMessageContaining("permission has not been granted");
        }

        assertThat(config.get(GraphDatabaseSettings.log_queries)).isEqualTo(before);
    }

    @Test
    void aCallerWithFullRightsIsAdmitted() {
        try (var tx = database.beginTransaction(KernelTransaction.Type.EXPLICIT, LoginContext.AUTH_DISABLED)) {
            tx.execute("CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')")
                    .accept(row -> true);
            tx.commit();
        }
        Config config = database.getDependencyResolver().resolveDependency(Config.class);

        assertThat(config.get(GraphDatabaseSettings.log_queries)).isEqualTo(GraphDatabaseSettings.LogQueryLevel.INFO);
    }

    /** A reader who may run procedures but has not been granted admin procedures. */
    private static LoginContext withoutAdminRights() {
        return new LoginContext(AuthSubject.ANONYMOUS, EMBEDDED_CONNECTION) {
            @Override
            public SecurityContext authorize(
                    IdLookup idLookup,
                    PrivilegeDatabaseReference dbReference,
                    AbstractSecurityLog securityLog,
                    long timeOfEvaluationMillis) {
                return denyingAdminProcedures(StaticAccessMode.READ, dbReference.name());
            }
        };
    }

    /**
     * The kernel derives the procedure's context from the caller's with {@code withMode}, so the denial has to survive
     * that derivation, as Neo4j's authorisation-aware contexts make theirs survive it.
     */
    private static SecurityContext denyingAdminProcedures(AccessMode mode, String database) {
        return new SecurityContext(AuthSubject.ANONYMOUS, mode, EMBEDDED_CONNECTION, database) {
            @Override
            public PermissionState allowExecuteAdminProcedure(int procedureId) {
                return PermissionState.NOT_GRANTED;
            }

            @Override
            public SecurityContext withMode(AccessMode mode) {
                return denyingAdminProcedures(mode, database());
            }
        };
    }
}
