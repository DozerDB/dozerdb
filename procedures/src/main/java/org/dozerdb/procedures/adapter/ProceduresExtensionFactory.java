/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.procedures.adapter;

import org.dozerdb.procedures.settings.RuntimeSettings;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

/**
 * Registers the module's procedures with the server's procedure registry as one GLOBAL Neo4j extension, discovered
 * through Java service lookup: the same mechanism that activates the metrics and query-log modules, so the procedures
 * exist in the shaded plugin JAR, the archives and the Docker image alike and need no change to core. Registration
 * goes through {@link GlobalProcedures} because Neo4j's own scan of annotated classes reads the {@code plugins/}
 * directory only and refuses the reserved {@code dbms.*} namespace; the registry accepts the namespace for a class
 * registered from inside the server, as it does for Community's built-in procedures.
 *
 * <p>Registration happens in {@code init()}: the registry exists by then (the DBMS creates it before the global
 * lifecycle starts) and the scan of {@code plugins/} runs later in the registry's own {@code start()}, where a copy of
 * this JAR misplaced there is logged and skipped rather than registered twice. A failure here stops the DBMS, as for
 * any GLOBAL extension; a server without its administration procedures is not one DozerDB wants to start silently.
 */
@ServiceProvider
public final class ProceduresExtensionFactory extends ExtensionFactory<Neo4jDependencies> {

    public static final String NAME = "dozerdb-procedures";

    public ProceduresExtensionFactory() {
        super(ExtensionType.GLOBAL, NAME);
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Neo4jDependencies dependencies) {
        return new LifecycleAdapter() {
            @Override
            public void init() throws ProcedureException {
                register(dependencies);
            }
        };
    }

    /**
     * The component comes first because the compiler resolves a procedure class's injected fields when it registers
     * the class. The component is built per call, so that the change is logged against the calling user.
     */
    static void register(Neo4jDependencies dependencies) throws ProcedureException {
        GlobalProcedures procedures = dependencies.globalProcedures();
        procedures.registerComponent(
                RuntimeSettings.class,
                context -> new ConfigurationAdapter(dependencies.config(), scopeOf(context)),
                true);
        procedures.registerProcedure(SetConfigValueProcedure.class);
    }

    /** Who the configuration's log line names as the author of a change. */
    private static String scopeOf(org.neo4j.kernel.api.procedure.Context context) {
        String user = context.securityContext().subject().executingUser();
        return user == null || user.isBlank()
                ? SetConfigValueProcedure.NAME
                : SetConfigValueProcedure.NAME + " called by user '" + user + "'";
    }
}
