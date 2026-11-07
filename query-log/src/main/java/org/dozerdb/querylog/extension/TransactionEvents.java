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

import org.dozerdb.querylog.entry.TransactionEntry;
import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;

/**
 * What the upstream adapter's transaction listener reports to the extension: the start of a kernel transaction and
 * its end, a commit or a rollback, from Neo4j's transaction execution monitor. Every event begins with one read of
 * {@link #configuration()}: when the transaction level is {@code OFF}, Neo4j's default, whatever the query level, the
 * listener returns at once, so a query log that writes no transaction entry costs that one read per transaction and
 * nothing else, and otherwise it passes the configuration it read along
 * with the cheap facts the policy decides from: the transaction id, the outcome (a rollback that Neo4j reports with a
 * failure is the failure; a commit or a client's own rollback is a success) and how long the transaction was open.
 * The entry itself is built only when it will be written, so the listener hands over a source rather than a record;
 * the copy from the kernel transaction then runs inside the isolation boundary too.
 */
public interface TransactionEvents {

    /** The configuration in force: one volatile read, the first thing a listener does for every event. */
    QueryLogConfiguration configuration();

    /** A transaction has started; the start entry is written at {@code VERBOSE} only. */
    void transactionStarted(QueryLogConfiguration configuration, long transactionId, Source entry);

    /**
     * A transaction has committed or rolled back after being open for {@code openMillis}; the end entry is written
     * when the policy says so for {@code outcome} under {@code configuration}.
     */
    void transactionEnded(
            QueryLogConfiguration configuration, long transactionId, Outcome outcome, long openMillis, Source entry);

    /** Copies Neo4j's kernel transaction into an entry; called at most once, inside the isolation boundary. */
    @FunctionalInterface
    interface Source {
        TransactionEntry entry();
    }
}
