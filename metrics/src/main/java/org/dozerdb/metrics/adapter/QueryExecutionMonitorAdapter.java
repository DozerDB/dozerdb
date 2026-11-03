/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.impl.query.QueryExecutionMonitor;
import org.neo4j.monitoring.Monitors;

/**
 * Adapts Neo4j's {@link QueryExecutionMonitor} events on one database's own {@link Monitors} bus
 * (at Neo4j 5.26.32 a plain {@code Monitors} that {@code AbstractDatabase} builds as a child of the global bus and
 * satisfies in the database's dependencies) to {@link QueryExecutions}.
 *
 * <p>Neo4j fires the monitor at two levels. The query router's {@code QueryStatementLifecycles} creates one monitor
 * on the global bus and one on the database's bus, and fires exactly one of them per event: the database's once the
 * executing query is bound to a database transaction, the global one otherwise (a query that fails before it reaches a
 * database, such as a parse error over Bolt). The Cypher engine's own monitor ({@code ExecutionEngine.scala}) is on the
 * database's bus too and fires for embedded queries, whose end the router never sees. Events on the database's bus
 * bubble up to the global bus, so a listener there would hear every database's queries once more. This adapter
 * therefore listens on the database's bus only: every query of the database is heard exactly once, with database
 * attribution for free, and a query that never reached the database is not counted for it.
 *
 * <p>The monitor's signatures (two {@code endFailure} overloads, the {@code ErrorGqlStatusObject} parameter) are the
 * module's most fragile hook after the web server's thread info; a Neo4j upgrade
 * checks them here first (watchlist).
 */
final class QueryExecutionMonitorAdapter implements QueryExecutions {

    private final Monitors databaseMonitors;
    private volatile Listener listener;

    QueryExecutionMonitorAdapter(Monitors databaseMonitors) {
        this.databaseMonitors = databaseMonitors;
    }

    @Override
    public void observeQueries(QueryExecutionEvents events) {
        Listener added = new Listener(events);
        databaseMonitors.addMonitorListener(added);
        listener = added;
    }

    @Override
    public void stopObservingQueries() {
        Listener removed = listener;
        if (removed != null) {
            listener = null;
            databaseMonitors.removeMonitorListener(removed);
        }
    }

    private static final class Listener implements QueryExecutionMonitor {

        private final QueryExecutionEvents events;

        private Listener(QueryExecutionEvents events) {
            this.events = events;
        }

        @Override
        public void startProcessing(ExecutingQuery query) {}

        @Override
        public void startExecution(ExecutingQuery query) {}

        @Override
        public void endFailure(ExecutingQuery query, Throwable failure) {
            events.queryFailed(query.cypherRuntime());
        }

        @Override
        public void endFailure(
                ExecutingQuery query, String reason, Status status, ErrorGqlStatusObject errorGqlStatusObject) {
            events.queryFailed(query.cypherRuntime());
        }

        @Override
        public void endSuccess(ExecutingQuery query) {
            events.querySucceeded(query.cypherRuntime(), query.elapsedMillis());
        }
    }
}
