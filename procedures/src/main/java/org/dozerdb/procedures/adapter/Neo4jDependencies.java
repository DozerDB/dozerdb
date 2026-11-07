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

import org.neo4j.configuration.Config;
import org.neo4j.kernel.api.procedure.GlobalProcedures;

/**
 * The Neo4j services the procedures module needs, declared as the dependencies interface of the extension factory.
 * Neo4j implements it with a proxy that resolves each method from the global dependency resolver when it is called,
 * so nothing is resolved while the extension is constructed.
 */
public interface Neo4jDependencies {

    /**
     * The server's procedure registry, which every delivery form has: a class registered here is callable whether the
     * JAR sits in {@code lib/} or {@code plugins/}, unlike the annotation scan, which reads {@code plugins/} alone and
     * refuses the reserved {@code dbms.*} namespace.
     */
    GlobalProcedures globalProcedures();

    /** The server's live configuration, whose dynamic-by-user path applies a runtime setting change. */
    Config config();
}
