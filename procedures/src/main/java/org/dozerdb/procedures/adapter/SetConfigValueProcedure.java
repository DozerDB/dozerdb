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

import static org.neo4j.procedure.Mode.DBMS;

import org.dozerdb.procedures.settings.RuntimeSettings;
import org.dozerdb.procedures.settings.SettingChange;
import org.neo4j.kernel.api.procedure.SystemProcedure;
import org.neo4j.procedure.Admin;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

/**
 * {@code dbms.setConfigValue}, with the name, signature, mode, description and admin-only marking the Operations
 * Manual's procedures reference gives Neo4j's, so that the manual's examples run unchanged. Neo4j's procedure compiler
 * builds the signature from these annotations exactly as it does for Community's {@code dbms.listConfig}; the
 * {@link Admin} marking makes the kernel refuse any caller whose security context does not grant admin procedures,
 * and the system-procedure marking lets the call run against the {@code system} database as well as a user database.
 *
 * <p>The class is public with a public no-argument constructor because the procedure compiler instantiates it per
 * call and injects the {@link Context} field; the field's type is a component the extension registers for this
 * module, so the class itself needs nothing from Neo4j beyond its public procedure API.
 */
public final class SetConfigValueProcedure {

    public static final String NAME = "dbms.setConfigValue";

    public static final String DESCRIPTION = "Update a given setting value. Passing an empty value results in "
            + "removing the configured value and falling back to the default value. Changes do not persist and are "
            + "lost if the server is restarted. In a clustered environment, `dbms.setConfigValue` affects only the "
            + "cluster member it is run against.";

    @Context
    public RuntimeSettings settings;

    @Admin
    @SystemProcedure
    @Description(DESCRIPTION)
    @Procedure(name = NAME, mode = DBMS)
    public void setConfigValue(
            @Name(value = "setting", description = "The name of the setting.") String setting,
            @Name(value = "value", description = "The value to set.") String value) {
        SettingChange.apply(settings, setting, value);
    }
}
