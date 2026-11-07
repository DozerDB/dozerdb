/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.extension;

/**
 * The query log's sink: Neo4j's {@code QueryLogger}, whose appender, pattern, rotation and JSON template the
 * operator's {@code server-logs.xml} defines. The upstream adapter implements it over Neo4j's map message, so that
 * one write serves whichever layout the operator configured: the rendered entry's line is the {@code %m} of the plain
 * layout and its fields are the JSON object's.
 */
public interface QueryLogWriter {

    /** Writes an entry at {@code INFO}: a success entry, a start entry, a transaction entry. */
    void info(RenderedEntry entry);

    /** Writes an entry at {@code ERROR}: a failed-query entry or a fallback entry; a non-null cause follows the line. */
    void error(RenderedEntry entry, Throwable cause);
}
