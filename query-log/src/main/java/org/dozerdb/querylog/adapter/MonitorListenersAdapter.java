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

import org.dozerdb.querylog.extension.MonitorListeners;
import org.dozerdb.querylog.extension.QueryEvents;
import org.dozerdb.querylog.extension.TransactionEvents;
import org.neo4j.monitoring.Monitors;

/**
 * Registers the query log's two listeners on Neo4j's global {@link Monitors} bus and removes them again.
 *
 * <p>The global bus is the right place, and the only one: each database's bus is a one-level child that forwards
 * every invocation to its parent once, the query router fires each event on either its global proxy (a query not yet
 * bound to a database, including every failure that happens before binding) or the database proxy, never both, and
 * the Cypher engine fires embedded queries on the database proxy only. One listener here therefore hears every
 * database's events exactly once, plus the pre-binding failures no database bus ever sees. The metrics module listens
 * on the database buses instead and misses those by design; the query log must not.
 *
 * <p>Removal is by object identity ({@code removeMonitorListener} compares with {@code equals}); the global bus has no
 * lifecycle of its own and never drops a listener by itself.
 */
public final class MonitorListenersAdapter implements MonitorListeners {

    private final Monitors monitors;
    private QueryExecutionListener queries;
    private TransactionExecutionListener transactions;

    private MonitorListenersAdapter(Monitors monitors) {
        this.monitors = monitors;
    }

    public static MonitorListeners of(Neo4jDependencies dependencies) {
        return new MonitorListenersAdapter(dependencies.monitors());
    }

    @Override
    public void register(QueryEvents queryEvents, TransactionEvents transactionEvents) {
        queries = new QueryExecutionListener(queryEvents);
        monitors.addMonitorListener(queries);
        transactions = new TransactionExecutionListener(transactionEvents);
        monitors.addMonitorListener(transactions);
    }

    @Override
    public void unregister() {
        if (queries != null) {
            monitors.removeMonitorListener(queries);
            queries = null;
        }
        if (transactions != null) {
            monitors.removeMonitorListener(transactions);
            transactions = null;
        }
    }
}
