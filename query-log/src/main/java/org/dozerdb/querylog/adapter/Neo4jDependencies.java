/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import org.neo4j.configuration.Config;
import org.neo4j.logging.internal.LogService;
import org.neo4j.monitoring.Monitors;

/**
 * The Neo4j services the query log needs, declared as the dependencies interface of the extension factory. Neo4j
 * implements it with a proxy that resolves each method from the global dependency resolver when it is called, so
 * nothing is resolved while the extension is constructed.
 */
public interface Neo4jDependencies {

    /** The global monitor bus, on which every database's query and transaction events arrive once. */
    Monitors monitors();

    /**
     * The server's log service: its internal log provider owns the {@code QueryLogger} the entries go through and the
     * {@code debug.log} the failures are reported in.
     */
    LogService logService();

    /**
     * The server's configuration, which declares the {@code db.logs.query.*} settings the query log reads and whose
     * change listeners make a runtime change reach the next entry.
     */
    Config config();
}
