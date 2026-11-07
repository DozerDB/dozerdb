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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The isolation boundary of the query log: every render and write, and the lifecycle's
 * set-up and tear-down, run inside it, so nothing thrown by the module ever reaches Neo4j; the query or transaction
 * completes as if the log were off. Unlike the metrics boundary, which disables a failing component, this one keeps
 * the log running and makes the gap visible where an auditor looks: a fallback entry at {@code ERROR} in
 * {@code query.log} names the query id and the exception, and the server log gets one error per distinct failure
 * kind (callback and exception class), so a deterministic bug never floods it. A fallback entry that cannot be
 * written itself is swallowed and counted. The fallback entry is rendered here, never by the entry renderers, so the
 * renderer that failed cannot fail it too; under the JSON layout it is a {@code query} entry with the {@code id} and
 * the text as its {@code failureReason}, or for a transaction entry a {@code transaction} entry with the
 * {@code transactionId}.
 */
final class IsolationBoundary {

    static final String FALLBACK_PREFIX = "Query log entry could not be written: id:";
    static final String TRANSACTION_FALLBACK_PREFIX = "Query log entry could not be written: transaction id:";

    private final QueryLogWriter queryLog;
    private final ServerLog serverLog;
    private final Set<String> reportedKinds = ConcurrentHashMap.newKeySet();
    private final AtomicLong fallbackEntriesLost = new AtomicLong();

    IsolationBoundary(QueryLogWriter queryLog, ServerLog serverLog) {
        this.queryLog = queryLog;
        this.serverLog = serverLog;
    }

    /** Which fallback entry marks a failed entry: a query entry naming the query id, or a transaction entry naming the transaction id. */
    enum Fallback {
        QUERY(FALLBACK_PREFIX) {
            @Override
            RenderedEntry entry(long id, String text) {
                return RenderedEntry.fallback(id, text);
            }
        },
        TRANSACTION(TRANSACTION_FALLBACK_PREFIX) {
            @Override
            RenderedEntry entry(long id, String text) {
                return RenderedEntry.transactionFallback(id, text);
            }
        };

        private final String prefix;

        Fallback(String prefix) {
            this.prefix = prefix;
        }

        abstract RenderedEntry entry(long id, String text);

        RenderedEntry entryFor(long id, Throwable failure) {
            return entry(id, prefix + id + " - " + describe(failure));
        }
    }

    /**
     * Runs the rendering and writing of one entry of a query or transaction with the given id; a failure leaves the
     * fallback entry of that kind in its place.
     */
    void guardEntry(Fallback fallback, String callback, long id, Action action) {
        try {
            action.run();
        } catch (Exception | LinkageError e) {
            reportOnce(callback, e);
            writeFallbackEntry(fallback.entryFor(id, e));
        }
    }

    /** Runs a lifecycle step; a failure is reported once and nothing else happens. */
    void guard(String callback, Action action) {
        try {
            action.run();
        } catch (Exception | LinkageError e) {
            reportOnce(callback, e);
        }
    }

    /** How many fallback entries could not be written either; the count is the only trace they leave. */
    long fallbackEntriesLost() {
        return fallbackEntriesLost.get();
    }

    private void writeFallbackEntry(RenderedEntry fallback) {
        try {
            queryLog.error(fallback, null);
        } catch (Exception | LinkageError fallbackFailure) {
            fallbackEntriesLost.incrementAndGet();
        }
    }

    private void reportOnce(String callback, Throwable failure) {
        String kind = callback + '/' + failure.getClass().getName();
        if (!reportedKinds.add(kind)) {
            return;
        }
        try {
            serverLog.error(
                    "Query log '" + callback + "' failed with "
                            + failure.getClass().getName()
                            + "; the server continues and a fallback entry marks each affected entry in query.log: "
                            + failure.getMessage(),
                    failure);
        } catch (Exception | LinkageError ignored) {
            // The server log is Neo4j's own; if even that fails there is nowhere left to report to.
        }
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }
}
