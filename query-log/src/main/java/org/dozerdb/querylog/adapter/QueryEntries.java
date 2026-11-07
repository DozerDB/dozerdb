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

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import java.util.Optional;
import java.util.OptionalLong;
import org.dozerdb.querylog.entry.QueryEntry;
import org.dozerdb.querylog.extension.QueryLogConfiguration;
import org.neo4j.cypher.internal.CypherVersion;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.api.query.QuerySnapshot;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.values.virtual.MapValue;

/**
 * Copies a query into a {@link QueryEntry}. One {@link QuerySnapshot} is taken, a consistent copy of the query's live
 * state, and every field is converted here: micros to millis, the database id to its name or {@code <none>}, the
 * connection to Neo4j's connection details, the parameters to their Cypher map, the Cypher version to the manual's
 * {@code CYPHER 5} / {@code CYPHER 25}, the cache key hash to its eight hex digits. The configuration in force when
 * the event arrived shapes the copy: the detail level decides the {@code id:} prefix (at {@code VERBOSE} only), and
 * the detail flags decide the text, the parameters and the failure.
 *
 * <p><b>Text and parameters.</b> The obfuscated text and parameters are what the entry shows: Community computes them
 * once per compiled query and caches them with it, masking passwords, sensitive procedure arguments and credential
 * URLs whatever the settings, and every literal besides under {@code db.logs.query.obfuscate_literals}, so a changed
 * setting applies to a cached query after {@code CALL db.clearQueryCaches()} as the manual says. They are absent for a
 * query that failed before its obfuscator existed (a parse or semantic error); the raw text and parameters then stand
 * in, so that the record stays complete, except that under {@code obfuscate_literals} the text is withheld, which is
 * what the manual documents for a query that cannot be parsed. Parameters are never literal-obfuscated (the manual
 * points to {@code parameter_logging_enabled} for that): with it off the entry carries no parameters and both layouts
 * drop the segment; with it on each value is cut to {@code db.logs.query.max_parameter_length}.
 *
 * <p><b>Start entry.</b> The same copy with the timing, allocation and page figures zeroed and no runtime: Neo4j fires
 * {@code startExecution} once the query is planned, so the snapshot already holds a planning time and a runtime that
 * the manual's start entry does not show.
 *
 * <p><b>Early start entry.</b> Under {@code db.logs.query.early_raw_logging_enabled} the start entry is written when
 * the query is received, before it is parsed, from the raw text and parameters, the only ones that exist yet; the
 * manual describes the setting as logging "without obfuscating passwords", so a password literal reaches this one
 * entry in clear while the end entry masks it as always. The setting lifts obfuscation and nothing else:
 * {@code parameter_logging_enabled} and {@code max_parameter_length} apply to the raw parameters too. Everything the
 * query acquires later is fixed, so the early start entry has one shape on every path and a reader can tell it from a
 * start entry written after planning: {@code transaction id:0} and the database {@code <none>} (the query has reached
 * neither; the end entry carries both), the zeroed figures, no runtime, the unplanned cache key hash and no query
 * language, which Neo4j settles in the pre-parse step after this event.
 *
 * <p><b>Plan.</b> An end entry carries the plan table ({@link QueryPlans}) when the plan description setting is on and
 * the query has a plan description; a start entry never does, because the manual's start entry shows nothing a plan
 * would add and the table belongs to the query's outcome.
 *
 * <p><b>Failed-query entry.</b> The reason Neo4j gave, the failure itself for the stack trace, and the GQL status
 * object copied into the {@code errorInfo} tree. Under {@code db.logs.query.obfuscate_errors} the message can embed
 * data values, so the obfuscated status description replaces it in the reason and throughout the tree, and the
 * throwable is dropped so that no stack trace follows; a failure without a status object then carries no reason at
 * all, since nothing safe could stand in.
 */
final class QueryEntries {

    private QueryEntries() {}

    static QueryEntry started(ExecutingQuery query, QueryLogConfiguration configuration) {
        QuerySnapshot snapshot = query.snapshot();
        return common(snapshot, configuration)
                .kind(QueryEntry.Kind.START)
                .executionPlanCacheKeyHash(QueryEntry.UNPLANNED_CACHE_KEY_HASH)
                .build();
    }

    /** The early start entry: the raw copy at receipt, with everything the query has not reached yet fixed. */
    static QueryEntry received(ExecutingQuery query, QueryLogConfiguration configuration) {
        QuerySnapshot snapshot = query.snapshot();
        return identity(snapshot, configuration)
                .kind(QueryEntry.Kind.START)
                .transactionId(QueryEntry.UNBOUND_TRANSACTION_ID)
                .database(QueryEntry.NO_DATABASE)
                .queryText(snapshot.rawQueryText())
                .parameters(parameters(snapshot.rawQueryParameters(), configuration))
                .executionPlanCacheKeyHash(QueryEntry.UNPLANNED_CACHE_KEY_HASH)
                .build();
    }

    static QueryEntry succeeded(ExecutingQuery query, QueryLogConfiguration configuration) {
        return ended(query.snapshot(), configuration)
                .kind(QueryEntry.Kind.SUCCESS)
                .build();
    }

    static QueryEntry failed(
            ExecutingQuery query,
            String reason,
            Throwable cause,
            ErrorGqlStatusObject gqlStatus,
            QueryLogConfiguration configuration) {
        return failure(
                        ended(query.snapshot(), configuration),
                        reason,
                        cause,
                        gqlStatus,
                        configuration.obfuscateErrors())
                .build();
    }

    /** The failure part of a failed-query entry, with the message, the throwable and the tree as the flag asks. */
    static QueryEntry.Builder failure(
            QueryEntry.Builder entry,
            String reason,
            Throwable cause,
            ErrorGqlStatusObject gqlStatus,
            boolean obfuscate) {
        entry.kind(QueryEntry.Kind.FAIL).errorInfo(ErrorInfos.of(gqlStatus, obfuscate));
        if (obfuscate) {
            return entry.failureReason(gqlStatus == null ? null : ObfuscatedErrorDescriptions.of(gqlStatus))
                    .failureCause(null);
        }
        return entry.failureReason(reason).failureCause(cause);
    }

    /**
     * The fields of an end entry. The plan table is read only when {@code db.logs.query.plan_description_enabled} is
     * on, because Neo4j builds the description afresh on every call; a query without a plan description (a compile
     * failure, a composite parent query) has no table whatever the setting.
     */
    private static QueryEntry.Builder ended(QuerySnapshot snapshot, QueryLogConfiguration configuration) {
        String runtime = snapshot.runtime();
        OptionalLong cpuMicros = snapshot.cpuTimeMicros();
        return common(snapshot, configuration)
                .queryPlan(configuration.planDescriptionEnabled() ? QueryPlans.tableOf(snapshot) : null)
                .elapsedMillis(MICROSECONDS.toMillis(snapshot.elapsedTimeMicros()))
                .planningMillis(MICROSECONDS.toMillis(snapshot.compilationTimeMicros()))
                .cpuMillis(cpuMicros.isPresent() ? MICROSECONDS.toMillis(cpuMicros.getAsLong()) : null)
                .waitingMillis(MICROSECONDS.toMillis(snapshot.waitTimeMicros()))
                .allocatedBytes(snapshot.allocatedBytes())
                .pageHits(snapshot.pageHits())
                .pageFaults(snapshot.pageFaults())
                .runtime(runtime == null || runtime.isEmpty() ? null : runtime)
                .executionPlanCacheKeyHash(String.format("%08X", snapshot.executionPlanCacheKeyHash()));
    }

    /** The fields a start entry and an end entry share: identity, source, text and transaction metadata. */
    private static QueryEntry.Builder common(QuerySnapshot snapshot, QueryLogConfiguration configuration) {
        return identity(snapshot, configuration)
                .transactionId(snapshot.transactionSequenceNumber())
                .database(snapshot.databaseId().map(NamedDatabaseId::name).orElse(QueryEntry.NO_DATABASE))
                .queryText(queryText(snapshot, configuration))
                .parameters(parameters(snapshot, configuration));
    }

    /** The fields every entry has from the moment the query is received: identity, source, users and metadata. */
    private static QueryEntry.Builder identity(QuerySnapshot snapshot, QueryLogConfiguration configuration) {
        CypherVersion language = snapshot.queryLanguage();
        return QueryEntry.builder()
                .detailLevel(configuration.detailLevel())
                .queryId(snapshot.internalQueryId())
                .source(snapshot.clientConnection().asConnectionDetails())
                .executingUser(snapshot.executingUsername())
                .authenticatedUser(snapshot.authenticatedUsername())
                .annotationData(snapshot.transactionAnnotationData())
                .queryLanguage(language == null ? null : language.description);
    }

    private static String queryText(QuerySnapshot snapshot, QueryLogConfiguration configuration) {
        Optional<String> obfuscated = snapshot.obfuscatedQueryText();
        if (obfuscated.isPresent()) {
            return obfuscated.get();
        }
        return configuration.obfuscateLiterals() ? null : snapshot.rawQueryText();
    }

    private static String parameters(QuerySnapshot snapshot, QueryLogConfiguration configuration) {
        return parameters(snapshot.obfuscatedQueryParameters().orElseGet(snapshot::rawQueryParameters), configuration);
    }

    private static String parameters(MapValue parameters, QueryLogConfiguration configuration) {
        if (!configuration.parameterLoggingEnabled()) {
            return null;
        }
        return ParameterMaps.render(parameters, configuration.maxParameterLength());
    }
}
