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
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.DetailLevel;

/**
 * The eleven {@code db.logs.query.*} values in force, as one immutable record. The module declares no setting of its
 * own: the upstream adapter reads Community's declarations and rebuilds this whole record whenever any one of them
 * changes through {@code dbms.setConfigValue}, so a monitor event reads one reference once and decides from a
 * consistent set of values; a change applies to the next event without a restart. The two detail levels and the two
 * thresholds decide which entries are written and the early raw logging flag at which of Neo4j's two start events the
 * start entry is written (the {@link LoggingPolicy}); the rest are the detail flags that shape an entry.
 *
 * @param detailLevel {@code db.logs.query.enabled}: which query entries are written
 * @param threshold {@code db.logs.query.threshold}: the elapsed time a successful query must reach at {@code INFO}
 * @param transactionDetailLevel {@code db.logs.query.transaction.enabled}: which transaction entries are written
 * @param transactionThreshold {@code db.logs.query.transaction.threshold}: the open time a transaction must reach at
 *     {@code INFO}
 * @param parameterLoggingEnabled {@code db.logs.query.parameter_logging_enabled}: whether the parameters segment is
 *     written
 * @param maxParameterLength {@code db.logs.query.max_parameter_length}: the length each parameter value is cut to
 * @param obfuscateLiterals {@code db.logs.query.obfuscate_literals}: whether the query text's literals are masked
 * @param obfuscateErrors {@code db.logs.query.obfuscate_errors}: whether a failure is written as its status
 *     description alone
 * @param planDescriptionEnabled {@code db.logs.query.plan_description_enabled}: whether the plan table follows the
 *     entry
 * @param earlyRawLoggingEnabled {@code db.logs.query.early_raw_logging_enabled}: whether the start entry is written
 *     when the query is received, before it is parsed, with its raw text and parameters, rather than once it is parsed
 *     and obfuscated
 * @param annotationDataFormat {@code db.logs.query.annotation_data_format}: how the JSON layout writes a
 *     transaction's metadata
 */
public record QueryLogConfiguration(
        DetailLevel detailLevel,
        Duration threshold,
        DetailLevel transactionDetailLevel,
        Duration transactionThreshold,
        boolean parameterLoggingEnabled,
        int maxParameterLength,
        boolean obfuscateLiterals,
        boolean obfuscateErrors,
        boolean planDescriptionEnabled,
        boolean earlyRawLoggingEnabled,
        AnnotationDataFormat annotationDataFormat) {

    public QueryLogConfiguration {
        Objects.requireNonNull(detailLevel, "detailLevel");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(transactionDetailLevel, "transactionDetailLevel");
        Objects.requireNonNull(transactionThreshold, "transactionThreshold");
        Objects.requireNonNull(annotationDataFormat, "annotationDataFormat");
    }

    /** Whether the query log writes nothing at all: both detail levels are {@code OFF}. */
    public boolean off() {
        return queriesOff() && transactionsOff();
    }

    /**
     * Whether no query entry is written: {@code db.logs.query.enabled} is {@code OFF}, whatever the transaction level.
     * This is the fast path the query listener takes before it allocates anything, so that a server logging
     * transactions alone, or nothing, pays one read per query event.
     */
    public boolean queriesOff() {
        return detailLevel == DetailLevel.OFF;
    }

    /**
     * Whether no transaction entry is written: {@code db.logs.query.transaction.enabled} is {@code OFF}, whatever the
     * query level. The transaction listener's fast path, taken on every transaction event of a server with Neo4j's
     * default transaction level.
     */
    public boolean transactionsOff() {
        return transactionDetailLevel == DetailLevel.OFF;
    }
}
