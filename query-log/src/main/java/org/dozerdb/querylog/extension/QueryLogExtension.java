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
import org.dozerdb.querylog.entry.TransactionEntry;
import org.dozerdb.querylog.extension.IsolationBoundary.Fallback;
import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;
import org.dozerdb.querylog.extension.LoggingPolicy.StartEvent;

/**
 * The lifecycle of the query log inside one server. Neo4j drives it through the upstream adapter's extension
 * factory, which is the composition root: it hands over the {@link UpstreamServices} and the renderer, so nothing
 * here names a Neo4j type or an adapter class. All set-up happens in {@link #init()}, inside the isolation boundary:
 * the {@code QueryLogger} handle, the server log, the first configuration snapshot and the listeners that follow its
 * changes, the ring of ended query ids and both monitor listeners on the global bus. {@code start()} and
 * {@code stop()} are no-ops, and {@link #shutdown()} removes the monitor listeners and the configuration listeners.
 *
 * <p>The configuration lives in one {@code volatile} field that the configuration listeners replace whole and that
 * every monitor event reads once, through {@link #configuration()}, before anything else; the adapter's listener
 * returns at once when its own side's detail level is {@code OFF}, and so does the method here, before a policy is
 * built, so that a server logging one kind of entry pays nothing for the other. Every start event then goes through
 * {@link #queryStarted} and every end event through {@link #queryEnded}: the {@link LoggingPolicy} decides from the detail level, the
 * outcome and the elapsed time whether an entry is written at all, the ring refuses a second end of the same query,
 * and only then is the entry copied from the snapshot, rendered for both layouts under the annotation data format the
 * configuration carries, and written, whatever fails in between becoming a fallback entry. Transaction events take
 * the same road through {@link #transactionStarted} and {@link #transactionEnded} under the transaction level and
 * threshold, with no ring: Neo4j reports each transaction's end once.
 */
public final class QueryLogExtension implements QueryEvents, TransactionEvents {

    private final UpstreamServices services;
    private final EntryRenderer renderer;
    private volatile QueryLogConfiguration configuration;
    private QueryLogWriter queryLog;
    private IsolationBoundary boundary;
    private EndedQueryIds ended;
    private DynamicConfiguration settings;
    private MonitorListeners listeners;

    public QueryLogExtension(UpstreamServices services, EntryRenderer renderer) {
        this.services = services;
        this.renderer = renderer;
    }

    public void init() {
        ServerLog serverLog;
        try {
            // The two logs come first: without them nothing can be reported. Neo4j registers its log service before
            // any extension, so only a Neo4j upgrade that moves them can fail here, and then there is nowhere to say
            // so.
            serverLog = services.serverLog().get();
            queryLog = services.queryLog().get();
        } catch (Exception | LinkageError e) {
            return;
        }
        boundary = new IsolationBoundary(queryLog, serverLog);
        boundary.guard("init", () -> {
            ended = new EndedQueryIds(EndedQueryIds.CAPACITY);
            // The configuration is in place before the first event can arrive, and its listeners before the first
            // change can. Each handle is kept before anything is added, so a registration that fails half-way is
            // still undone.
            settings = services.settings().get();
            configuration = settings.read();
            settings.register(changed -> configuration = changed);
            listeners = services.listeners().get();
            listeners.register(this, this);
        });
    }

    public void shutdown() {
        MonitorListeners removedListeners = listeners;
        DynamicConfiguration removedSettings = settings;
        listeners = null;
        settings = null;
        if (removedListeners != null) {
            boundary.guard("shutdown", removedListeners::unregister);
        }
        if (removedSettings != null) {
            boundary.guard("shutdown", removedSettings::unregister);
        }
    }

    @Override
    public QueryLogConfiguration configuration() {
        return configuration;
    }

    @Override
    public void queryStarted(
            QueryLogConfiguration configuration, StartEvent event, long queryId, QueryEvents.Source source) {
        if (configuration.queriesOff()
                || !LoggingPolicy.forQueries(configuration).writesStartEntry(event)) {
            return;
        }
        boundary.guardEntry(
                Fallback.QUERY,
                "query start",
                queryId,
                () -> queryLog.info(renderer.render(source.entry(), configuration.annotationDataFormat())));
    }

    @Override
    public void queryEnded(
            QueryLogConfiguration configuration,
            long queryId,
            Outcome outcome,
            long elapsedMillis,
            QueryEvents.Source source) {
        if (configuration.queriesOff()
                || !LoggingPolicy.forQueries(configuration).writesEndEntry(outcome, elapsedMillis)) {
            return;
        }
        boundary.guardEntry(Fallback.QUERY, "query end", queryId, () -> {
            if (!ended.markEnded(queryId)) {
                return;
            }
            QueryEntry entry = source.entry();
            RenderedEntry rendered = renderer.render(entry, configuration.annotationDataFormat());
            if (entry.failed()) {
                queryLog.error(rendered, entry.failureCause());
            } else {
                queryLog.info(rendered);
            }
        });
    }

    @Override
    public void transactionStarted(
            QueryLogConfiguration configuration, long transactionId, TransactionEvents.Source source) {
        if (configuration.transactionsOff()
                || !LoggingPolicy.forTransactions(configuration).writesStartEntry()) {
            return;
        }
        boundary.guardEntry(
                Fallback.TRANSACTION,
                "transaction start",
                transactionId,
                () -> queryLog.info(renderer.render(source.entry())));
    }

    @Override
    public void transactionEnded(
            QueryLogConfiguration configuration,
            long transactionId,
            Outcome outcome,
            long openMillis,
            TransactionEvents.Source source) {
        if (configuration.transactionsOff()
                || !LoggingPolicy.forTransactions(configuration).writesEndEntry(outcome, openMillis)) {
            return;
        }
        boundary.guardEntry(Fallback.TRANSACTION, "transaction end", transactionId, () -> {
            TransactionEntry entry = source.entry();
            RenderedEntry rendered = renderer.render(entry);
            if (entry.failed()) {
                // Neo4j's structured log offers a throwable at ERROR only, and a rollback caused by a failure is
                // what an auditor greps ERROR for, as with a failed-query entry.
                queryLog.error(rendered, entry.failureCause());
            } else {
                queryLog.info(rendered);
            }
        });
    }
}
