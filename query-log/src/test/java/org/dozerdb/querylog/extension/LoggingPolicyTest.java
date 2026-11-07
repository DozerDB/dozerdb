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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.DetailLevel;
import org.dozerdb.querylog.extension.LoggingPolicy.Outcome;
import org.dozerdb.querylog.extension.LoggingPolicy.StartEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The level and threshold rules of the query log, as the Operations Manual describes {@code db.logs.query.enabled}
 * and {@code db.logs.query.threshold}: {@code OFF} logs nothing; {@code INFO} logs a query at its end when it took
 * longer than the threshold; {@code VERBOSE} logs the start and the end of every query regardless of the threshold.
 * What the manual leaves open is settled here too: a failure is always written at {@code INFO}, whatever the
 * threshold, and a query that took exactly the threshold is written, so that the default threshold of {@code 0}
 * logs a sub-millisecond query. Transactions follow the same rules under their own two settings.
 */
class LoggingPolicyTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @ParameterizedTest(name = "{0}, {1}, {2} ms against a 1 s threshold -> end entry written: {3}")
    @CsvSource({
        // level,  outcome,  elapsedMillis, written
        "OFF,     SUCCESS,  999,  false",
        "OFF,     SUCCESS,  1000, false",
        "OFF,     SUCCESS,  1001, false",
        "OFF,     FAILURE,  999,  false",
        "OFF,     FAILURE,  1000, false",
        "OFF,     FAILURE,  1001, false",
        "INFO,    SUCCESS,  999,  false",
        "INFO,    SUCCESS,  1000, true",
        "INFO,    SUCCESS,  1001, true",
        "INFO,    FAILURE,  999,  true",
        "INFO,    FAILURE,  1000, true",
        "INFO,    FAILURE,  1001, true",
        "VERBOSE, SUCCESS,  999,  true",
        "VERBOSE, SUCCESS,  1000, true",
        "VERBOSE, SUCCESS,  1001, true",
        "VERBOSE, FAILURE,  999,  true",
        "VERBOSE, FAILURE,  1000, true",
        "VERBOSE, FAILURE,  1001, true",
    })
    void theEndEntryFollowsTheLevelTheOutcomeAndTheThreshold(
            DetailLevel level, Outcome outcome, long elapsedMillis, boolean written) {
        LoggingPolicy policy = new LoggingPolicy(level, ONE_SECOND);

        assertThat(policy.writesEndEntry(outcome, elapsedMillis)).isEqualTo(written);
    }

    @ParameterizedTest(name = "{0} -> start entry written: {1}")
    @CsvSource({"OFF, false", "INFO, false", "VERBOSE, true"})
    void theStartEntryIsWrittenAtVerboseOnly(DetailLevel level, boolean written) {
        LoggingPolicy policy = new LoggingPolicy(level, ONE_SECOND);

        assertThat(policy.writesStartEntry()).isEqualTo(written);
    }

    @ParameterizedTest(
            name = "{0}, early raw logging {1} -> start entry at the received event: {2}, at the parsed event: {3}")
    @CsvSource({
        // level,  earlyRawLogging, atReceived, atParsed
        "OFF,     false, false, false",
        "OFF,     true,  false, false",
        "INFO,    false, false, false",
        "INFO,    true,  false, false",
        "VERBOSE, false, false, true",
        "VERBOSE, true,  true,  false",
    })
    void exactlyOneOfTheTwoStartEventsWritesTheStartEntryAtVerboseAndNeitherBelow(
            DetailLevel level, boolean earlyRawLogging, boolean atReceived, boolean atParsed) {
        LoggingPolicy policy = new LoggingPolicy(level, ONE_SECOND, earlyRawLogging);

        assertThat(policy.writesStartEntry(StartEvent.RECEIVED)).isEqualTo(atReceived);
        assertThat(policy.writesStartEntry(StartEvent.PARSED)).isEqualTo(atParsed);
        assertThat(policy.writesStartEntry())
                .as("a start entry is written at all exactly when one of the two events writes it")
                .isEqualTo(atReceived || atParsed);
    }

    @Test
    void theTwoArgumentPolicyWritesTheStartEntryAtTheParsedEventAsTransactionsAndOlderCallersExpect() {
        LoggingPolicy policy = new LoggingPolicy(DetailLevel.VERBOSE, ONE_SECOND);

        assertThat(policy.earlyRawLogging()).isFalse();
        assertThat(policy.writesStartEntry(StartEvent.PARSED)).isTrue();
        assertThat(policy.writesStartEntry(StartEvent.RECEIVED)).isFalse();
    }

    @Test
    void theDefaultThresholdOfZeroLogsASubMillisecondQueryAtInfo() {
        LoggingPolicy policy = new LoggingPolicy(DetailLevel.INFO, Duration.ZERO);

        assertThat(policy.writesEndEntry(Outcome.SUCCESS, 0)).isTrue();
    }

    @Test
    void aThresholdBelowOneMillisecondStillHidesAQueryThatTookNoWholeMillisecond() {
        // The manual's threshold is a duration down to nanoseconds; the entry's elapsed time is whole milliseconds,
        // so a 500 microsecond threshold behaves as 0 ms and a 1500 microsecond one as 1 ms.
        assertThat(new LoggingPolicy(DetailLevel.INFO, Duration.ofNanos(500_000)).writesEndEntry(Outcome.SUCCESS, 0))
                .isTrue();
        assertThat(new LoggingPolicy(DetailLevel.INFO, Duration.ofNanos(1_500_000)).writesEndEntry(Outcome.SUCCESS, 0))
                .isFalse();
    }

    @Test
    void queriesAndTransactionsEachFollowTheirOwnLevelAndThreshold() {
        QueryLogConfiguration configuration = new QueryLogConfiguration(
                DetailLevel.INFO,
                Duration.ofMillis(10),
                DetailLevel.VERBOSE,
                Duration.ofMillis(500),
                true,
                Integer.MAX_VALUE,
                false,
                false,
                false,
                true,
                AnnotationDataFormat.JSON);

        LoggingPolicy queries = LoggingPolicy.forQueries(configuration);
        LoggingPolicy transactions = LoggingPolicy.forTransactions(configuration);

        assertThat(queries).isEqualTo(new LoggingPolicy(DetailLevel.INFO, Duration.ofMillis(10), true));
        // Early raw logging is a query setting: a transaction has no text to log before parsing.
        assertThat(transactions).isEqualTo(new LoggingPolicy(DetailLevel.VERBOSE, Duration.ofMillis(500), false));
        assertThat(queries.writesStartEntry()).isFalse();
        assertThat(transactions.writesStartEntry()).isTrue();
        assertThat(queries.writesEndEntry(Outcome.SUCCESS, 9)).isFalse();
        assertThat(transactions.writesEndEntry(Outcome.SUCCESS, 9)).isTrue();
    }
}
