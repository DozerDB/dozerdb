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

import org.dozerdb.querylog.extension.RenderedEntry;
import org.neo4j.logging.log4j.Neo4jMapMessage;

/**
 * The query log entry as Neo4j's logging sees it: one structured message that both of Neo4j's query log layouts can
 * read. {@code PatternLayout}'s {@code %m} asks the message for its string form, which is the rendered entry's plain
 * line; {@code QueryLogJsonLayout.json}'s map resolver reads the message's map, which holds the rendered entry's
 * fields, and merges them into the top-level JSON object in Log4j's sorted key order. So the manual's
 * {@code server-logs.xml} edit that swaps the layout needs nothing from DozerDB, and Neo4j's template and field names
 * apply unchanged. The message itself renders nothing: both forms come from the entry model's two renderers, through
 * the rendered entry.
 *
 * <p>A null value is never put into the map, so an absent field is absent from the JSON; the renderer guarantees that
 * and the map message would otherwise write {@code "key":null}.
 */
final class QueryLogMessage extends Neo4jMapMessage {

    private final String line;

    QueryLogMessage(RenderedEntry entry) {
        super(Math.max(1, entry.fields().size()));
        entry.fields().forEach(this::with);
        this.line = entry.line();
    }

    @Override
    protected void formatAsString(StringBuilder sb) {
        sb.append(line);
    }
}
