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

import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;
import org.dozerdb.querylog.extension.LoggingPolicy.StartEvent;
import org.dozerdb.querylog.extension.QueryEvents;
import org.dozerdb.querylog.extension.QueryLogConfiguration;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.impl.query.QueryExecutionMonitor;

/**
 * The query listener: Neo4j's {@link QueryExecutionMonitor} on the global bus, forwarding the two start events and the
 * two end events to the extension. Neo4j announces a start twice: {@code startProcessing} when the query is received,
 * before any parsing, when only the raw text and parameters exist, and {@code startExecution} once the query is parsed
 * and obfuscated. Both are forwarded with their own source, the raw copy or the obfuscated copy, and the policy writes
 * the start entry at exactly one of them: the parsed one by default, so that the text is masked, or the received one
 * under {@code db.logs.query.early_raw_logging_enabled}, so that a query that hangs or fails in parsing is recorded.
 * {@code startProcessing} is the only event that precedes the obfuscator (watchlist). End entries come from
 * {@code endSuccess} and {@code endFailure} only;
 * {@code beforeEnd} is never used (embedded queries do not fire it, and the double report the ring de-duplicates
 * doubles it too). The four-argument {@code endFailure} carries no throwable, but Cypher passes the exception itself as the GQL
 * status object when it is one, so that exception is the failure cause and gives the failed-query entry its stack
 * trace; the status object itself, from either overload, becomes the JSON {@code errorInfo}.
 *
 * <p>Each event first reads the configuration in force and returns when the query level is {@code OFF}, whatever the
 * transaction level, before anything is allocated; the configuration it read travels with the event, so the entry carries the detail level
 * that decided it and is shaped by the detail flags in force at that moment. The elapsed time handed over is Neo4j's own ({@code ExecutingQuery.elapsedMillis()}), a clock
 * read and no snapshot.
 *
 * <p>The monitor's signatures (two {@code endFailure} overloads, the {@code ErrorGqlStatusObject} parameter, the
 * {@code beforeEnd} and {@code planComputed} defaults) are this module's most fragile hook; a Neo4j upgrade checks
 * them here first (watchlist).
 */
final class QueryExecutionListener implements QueryExecutionMonitor {

    private final QueryEvents events;

    QueryExecutionListener(QueryEvents events) {
        this.events = events;
    }

    @Override
    public void startProcessing(ExecutingQuery query) {
        QueryLogConfiguration configuration = unlessQueriesOff();
        if (configuration == null) {
            return;
        }
        events.queryStarted(
                configuration,
                StartEvent.RECEIVED,
                query.internalQueryId(),
                () -> QueryEntries.received(query, configuration));
    }

    @Override
    public void startExecution(ExecutingQuery query) {
        QueryLogConfiguration configuration = unlessQueriesOff();
        if (configuration == null) {
            return;
        }
        events.queryStarted(
                configuration,
                StartEvent.PARSED,
                query.internalQueryId(),
                () -> QueryEntries.started(query, configuration));
    }

    @Override
    public void endFailure(ExecutingQuery query, Throwable failure) {
        failed(query, failure.getMessage(), failure, failure instanceof ErrorGqlStatusObject gql ? gql : null);
    }

    @Override
    public void endFailure(ExecutingQuery query, String reason, Status status, ErrorGqlStatusObject gqlStatus) {
        failed(query, reason, gqlStatus instanceof Throwable throwable ? throwable : null, gqlStatus);
    }

    @Override
    public void endSuccess(ExecutingQuery query) {
        QueryLogConfiguration configuration = unlessQueriesOff();
        if (configuration == null) {
            return;
        }
        events.queryEnded(
                configuration,
                query.internalQueryId(),
                Outcome.SUCCESS,
                query.elapsedMillis(),
                () -> QueryEntries.succeeded(query, configuration));
    }

    /**
     * The configuration in force, or {@code null} when the query level is {@code OFF}: the one volatile read every
     * event begins with, and nothing allocated on the way out.
     */
    private QueryLogConfiguration unlessQueriesOff() {
        QueryLogConfiguration configuration = events.configuration();
        return configuration.queriesOff() ? null : configuration;
    }

    private void failed(ExecutingQuery query, String reason, Throwable cause, ErrorGqlStatusObject gqlStatus) {
        QueryLogConfiguration configuration = unlessQueriesOff();
        if (configuration == null) {
            return;
        }
        events.queryEnded(
                configuration,
                query.internalQueryId(),
                Outcome.FAILURE,
                query.elapsedMillis(),
                () -> QueryEntries.failed(query, reason, cause, gqlStatus, configuration));
    }
}
