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

import java.time.Duration;
import java.util.Objects;
import org.dozerdb.querylog.entry.DetailLevel;

/**
 * Which entries are written under one detail level and one threshold: every level and threshold rule of the query log
 * lives here and nowhere else. The rules are the Operations Manual's description of {@code db.logs.query.enabled}
 * and {@code db.logs.query.threshold}, with what the manual leaves open settled in the audit log's favour:
 *
 * <ul>
 *   <li>{@code OFF}: nothing is written.
 *   <li>{@code INFO}: an end entry is written when the elapsed time reached the threshold
 *       ({@code elapsedMillis >= threshold}, so that the default threshold of {@code 0} logs a sub-millisecond query,
 *       which {@code >} would drop); a failure is written whatever the threshold, because a failed query is what an
 *       auditor looks for first; no start entry.
 *   <li>{@code VERBOSE}: the start entry and the end entry of every query, the threshold ignored.
 * </ul>
 *
 * <p>Neo4j announces a query's start twice: once when it is received, before anything is parsed, and once when it is
 * parsed, obfuscated and about to execute. The start entry is written at exactly one of them, and the policy says
 * which, so that no caller reads the setting twice and a query never gets two start entries: at the parsed event by
 * default, so that the text is already obfuscated, or at the received event under
 * {@code db.logs.query.early_raw_logging_enabled}, so that a query that hangs or fails in parsing is still recorded,
 * with its raw text. The decision is made from the configuration each event carried; a change of the setting while a
 * query is being parsed can leave that one query with two start entries or none, as a change of the detail level
 * during a query leaves it a start without an end.
 *
 * <p>Transactions follow the same rules under {@code db.logs.query.transaction.enabled} and
 * {@code db.logs.query.transaction.threshold}, the elapsed time being how long the transaction was open when it
 * committed or rolled back, and a rollback that carries a failure counting as the failure. The elapsed values come
 * from Neo4j's clocks in whole milliseconds, and so does the comparison: a threshold below one millisecond behaves as
 * {@code 0}.
 *
 * @param level the detail level in force
 * @param threshold the elapsed time an end entry must reach at {@code INFO}
 * @param earlyRawLogging whether the start entry is written when the query is received rather than once it is parsed
 */
public record LoggingPolicy(DetailLevel level, Duration threshold, boolean earlyRawLogging) {

    /** How a query or transaction ended; the threshold applies to a success only. */
    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    /**
     * The two events at which Neo4j announces a query's start: {@link #RECEIVED} before parsing, when only the raw
     * text and parameters exist, and {@link #PARSED} once the query is parsed and obfuscated, just before it executes.
     */
    public enum StartEvent {
        RECEIVED,
        PARSED
    }

    public LoggingPolicy {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(threshold, "threshold");
    }

    /**
     * A policy without early raw logging, whose start entry, if any, is written at the parsed event: the transaction
     * policy, because a transaction has no text to log before parsing and so no early raw flag.
     */
    public LoggingPolicy(DetailLevel level, Duration threshold) {
        this(level, threshold, false);
    }

    /**
     * The rules for query entries: {@code db.logs.query.enabled}, {@code db.logs.query.threshold} and
     * {@code db.logs.query.early_raw_logging_enabled}.
     */
    public static LoggingPolicy forQueries(QueryLogConfiguration configuration) {
        return new LoggingPolicy(
                configuration.detailLevel(), configuration.threshold(), configuration.earlyRawLoggingEnabled());
    }

    /**
     * The rules for transaction entries: {@code db.logs.query.transaction.enabled} and
     * {@code db.logs.query.transaction.threshold}.
     */
    public static LoggingPolicy forTransactions(QueryLogConfiguration configuration) {
        return new LoggingPolicy(configuration.transactionDetailLevel(), configuration.transactionThreshold());
    }

    /** Whether a start entry is written at all: at {@code VERBOSE} only. */
    public boolean writesStartEntry() {
        return level == DetailLevel.VERBOSE;
    }

    /**
     * Whether the start entry is written at {@code event}: at {@code VERBOSE} only, and then at exactly one of the two
     * events, the received one under early raw logging and the parsed one otherwise.
     */
    public boolean writesStartEntry(StartEvent event) {
        return writesStartEntry() && event == (earlyRawLogging ? StartEvent.RECEIVED : StartEvent.PARSED);
    }

    /** Whether the end entry of a query or transaction that ended as {@code outcome} after {@code elapsedMillis} is written. */
    public boolean writesEndEntry(Outcome outcome, long elapsedMillis) {
        return switch (level) {
            case OFF -> false;
            case VERBOSE -> true;
            case INFO -> outcome == Outcome.FAILURE || elapsedMillis >= threshold.toMillis();
        };
    }
}
