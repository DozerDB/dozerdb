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

import org.dozerdb.querylog.extension.QueryLogWriter;
import org.dozerdb.querylog.extension.RenderedEntry;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.log4j.LoggerTarget;

/**
 * The {@code QueryLogger} handle: the internal log provider's log for {@link LoggerTarget#QUERY_LOGGER}, the logger
 * Neo4j's shipped {@code server-logs.xml} routes to the {@code QueryLog} appender ({@code query.log}, the pattern
 * layout, 20 MB x 7) and whose layout the operator may swap for Neo4j's JSON template. The global log service is used,
 * not a database's, so no database name prefixes the line. Every entry is handed to Log4j synchronously on the thread
 * that ends the query, as Neo4j does, as one {@link QueryLogMessage} that serves either layout; a failed-query entry's
 * cause goes with it, so Log4j writes the stack trace after the line or as the JSON {@code stacktrace} field.
 */
public final class QueryLogWriterAdapter implements QueryLogWriter {

    private final InternalLog queryLogger;

    private QueryLogWriterAdapter(InternalLog queryLogger) {
        this.queryLogger = queryLogger;
    }

    public static QueryLogWriter of(Neo4jDependencies dependencies) {
        return new QueryLogWriterAdapter(
                dependencies.logService().getInternalLogProvider().getLog(LoggerTarget.QUERY_LOGGER));
    }

    @Override
    public void info(RenderedEntry entry) {
        queryLogger.info(new QueryLogMessage(entry));
    }

    @Override
    public void error(RenderedEntry entry, Throwable cause) {
        if (cause == null) {
            queryLogger.error(new QueryLogMessage(entry));
        } else {
            queryLogger.error(new QueryLogMessage(entry), cause);
        }
    }
}
