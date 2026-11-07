/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.entry;

import static org.dozerdb.querylog.entry.Rendering.appendText;

/**
 * Renders a query entry as the {@code %m} of the plain-text line Neo4j's {@code PatternLayout} writes to
 * {@code query.log}; the layout adds the timestamp and the level. The grammar is the Operations Manual 2026.10's
 * newest example for the segments it documents and DozerDB's own shape for the rest, segments joined by {@code " - "} except
 * the colon after the elapsed time and the tab between the source and the database:
 *
 * <pre>
 * [Query started: ][id:N - ]transaction id:N - N ms: (planning: N, waiting: N) - N B - N page hits, N page faults
 *   - SOURCE\tDATABASE - USER - QUERY[ - PARAMETERS] - runtime=RUNTIME - ANNOTATIONS[ - FAILURE REASON][\nPLAN TABLE]
 * </pre>
 *
 * <ul>
 *   <li>{@code Query started: } opens a start entry, whose figures the adapter zeroes.
 *   <li>{@code id:N} is the query id, at {@code VERBOSE} only.
 *   <li>The parameters segment is dropped, {@code " - "} included, when the entry carries none.
 *   <li>{@code runtime=null} spells an absent runtime; every other absent field leaves its segment empty.
 *   <li>The failure reason closes a failed-query entry when Neo4j reported one; the throwable itself goes to
 *       Log4j, not through here.
 *   <li>The plan table follows on the lines after the entry.
 * </ul>
 */
public final class PlainTextRenderer {

    static final String START_PREFIX = "Query started: ";

    private PlainTextRenderer() {}

    public static String render(QueryEntry entry) {
        StringBuilder line = new StringBuilder(256);
        if (entry.kind() == QueryEntry.Kind.START) {
            line.append(START_PREFIX);
        }
        if (entry.detailLevel() == DetailLevel.VERBOSE) {
            line.append("id:").append(entry.queryId()).append(" - ");
        }
        line.append("transaction id:")
                .append(entry.transactionId())
                .append(" - ")
                .append(entry.elapsedMillis())
                .append(" ms: (planning: ")
                .append(entry.planningMillis())
                .append(", waiting: ")
                .append(entry.waitingMillis())
                .append(") - ")
                .append(entry.allocatedBytes())
                .append(" B - ")
                .append(entry.pageHits())
                .append(" page hits, ")
                .append(entry.pageFaults())
                .append(" page faults - ");
        appendText(line, entry.source());
        line.append('\t');
        appendText(line, entry.database());
        line.append(" - ");
        appendText(line, entry.executingUser());
        line.append(" - ");
        appendText(line, entry.queryText());
        if (entry.parameters() != null) {
            line.append(" - ").append(entry.parameters());
        }
        line.append(" - runtime=").append(entry.runtime() == null ? "null" : entry.runtime());
        line.append(" - ").append(CypherMaps.render(entry.annotationData()));
        if (entry.failed() && entry.failureReason() != null) {
            line.append(" - ").append(entry.failureReason());
        }
        if (entry.queryPlan() != null) {
            line.append('\n').append(entry.queryPlan());
        }
        return line.toString();
    }
}
