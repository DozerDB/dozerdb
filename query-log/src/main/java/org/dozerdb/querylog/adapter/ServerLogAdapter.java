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

import org.dozerdb.querylog.extension.QueryLogExtension;
import org.dozerdb.querylog.extension.ServerLog;
import org.neo4j.logging.InternalLog;

/** Adapts Neo4j's internal log ({@code debug.log}) to {@link ServerLog}, under the extension's class as context. */
public final class ServerLogAdapter implements ServerLog {

    private final InternalLog log;

    private ServerLogAdapter(InternalLog log) {
        this.log = log;
    }

    public static ServerLog of(Neo4jDependencies dependencies) {
        return new ServerLogAdapter(dependencies.logService().getInternalLog(QueryLogExtension.class));
    }

    @Override
    public void error(String message, Throwable cause) {
        log.error(message, cause);
    }
}
