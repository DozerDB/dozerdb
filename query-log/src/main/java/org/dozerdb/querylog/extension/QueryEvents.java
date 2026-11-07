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

import org.dozerdb.querylog.entry.QueryEntry;
import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;
import org.dozerdb.querylog.extension.LoggingPolicy.StartEvent;

/**
 * What the upstream adapter's query listener reports to the extension: the start of a query, from each of Neo4j's two
 * start events (the query received, before parsing; the query parsed and about to execute), and its end, success or
 * failure, from the {@code endSuccess} and {@code endFailure} events and never from {@code beforeEnd}, which embedded
 * queries do not fire. Every event begins with one read of
 * {@link #configuration()}: when the query level is {@code OFF}, whatever the transaction level, the listener returns
 * at once, so a query log that writes no query entry costs that one read per query event and nothing else, and
 * otherwise it passes the configuration it read along with the cheap
 * facts (the query id, the outcome, the elapsed time) the policy decides from. The entry itself is built only when it
 * will be written, so the listener hands over a source rather than a record; the copy from the snapshot then runs
 * inside the isolation boundary too.
 */
public interface QueryEvents {

    /** The configuration in force: one volatile read, the first thing a listener does for every event. */
    QueryLogConfiguration configuration();

    /**
     * A query has started, at {@code event}; the start entry is written at {@code VERBOSE} only and at exactly one of
     * the two events, which the policy picks from the configuration. The source given for the received event copies the
     * raw text and parameters, the only ones that exist yet; the source for the parsed event copies the obfuscated ones.
     */
    void queryStarted(QueryLogConfiguration configuration, StartEvent event, long queryId, Source entry);

    /**
     * A query has ended after {@code elapsedMillis}; the end entry, a success entry or a failed-query entry, is
     * written when the policy says so for {@code outcome} under {@code configuration}.
     */
    void queryEnded(
            QueryLogConfiguration configuration, long queryId, Outcome outcome, long elapsedMillis, Source entry);

    /** Copies Neo4j's query snapshot into an entry; called at most once, inside the isolation boundary. */
    @FunctionalInterface
    interface Source {
        QueryEntry entry();
    }
}
