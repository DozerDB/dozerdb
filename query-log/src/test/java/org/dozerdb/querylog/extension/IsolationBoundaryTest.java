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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dozerdb.querylog.extension.IsolationBoundary.Fallback;
import org.dozerdb.querylog.inventory.FieldInventory;
import org.junit.jupiter.api.Test;

/**
 * A failure while an entry is rendered or written never reaches the caller and never silently drops the entry, so
 * that an audit log with marked gaps replaces one that silently stopped. The boundary writes a fallback entry at {@code ERROR} to the query log naming the query id and the
 * exception, reports each distinct failure kind once in the server log, keeps running, and swallows and counts a
 * fallback that fails itself. The query log and the server log are the two external surfaces; both are recorded here.
 */
class IsolationBoundaryTest {

    private final RecordingQueryLog queryLog = new RecordingQueryLog();
    private final RecordingServerLog serverLog = new RecordingServerLog();
    private final IsolationBoundary boundary = new IsolationBoundary(queryLog, serverLog);

    @Test
    void aFailingEntryBecomesAFallbackEntryNamingTheQueryIdAndTheException() {
        assertThatCode(() -> boundary.guardEntry(Fallback.QUERY, "query end", 34, () -> {
                    throw new IllegalStateException("renderer broke");
                }))
                .doesNotThrowAnyException();

        assertThat(queryLog.errors)
                .containsExactly(
                        "Query log entry could not be written: id:34 - java.lang.IllegalStateException: renderer broke");
        assertThat(queryLog.infos).isEmpty();
    }

    @Test
    void theFieldInventorysFallbackShapeShowsExactlyTheLinesTheBoundaryWrites() {
        // The fallback entry is the one shape no renderer produces, so the inventory carries its lines literally and
        // the compatibility page shows them; this keeps them the boundary's own.
        boundary.guardEntry(Fallback.QUERY, "query end", 34, () -> {
            throw new IllegalStateException("renderer broke");
        });
        boundary.guardEntry(Fallback.TRANSACTION, "transaction end", 12, () -> {
            throw new IllegalStateException("renderer broke");
        });

        JsonNode fallback = FieldInventory.load().shapes().stream()
                .filter(shape -> shape.get("id").asText().equals("fallback-entry"))
                .findFirst()
                .orElseThrow();
        List<String> lines = new ArrayList<>();
        fallback.get("lines").forEach(line -> lines.add(line.asText()));
        assertThat(lines).containsExactlyElementsOf(queryLog.errors);
    }

    @Test
    void theFallbackEntryIsAQueryEntryWithTheIdAndTheTextAsItsFailureReasonInTheJsonLayout() {
        boundary.guardEntry(Fallback.QUERY, "query end", 34, () -> {
            throw new IllegalStateException("renderer broke");
        });

        assertThat(queryLog.errorFields)
                .containsExactly(
                        Map.of(
                                "type",
                                "query",
                                "id",
                                "34",
                                "failureReason",
                                "Query log entry could not be written: id:34 - java.lang.IllegalStateException: renderer broke"));
    }

    @Test
    void aSuccessfulEntryWritesNothingElse() {
        boundary.guardEntry(
                Fallback.QUERY, "query end", 1, () -> queryLog.info(new RenderedEntry("the entry", Map.of())));

        assertThat(queryLog.infos).containsExactly("the entry");
        assertThat(queryLog.errors).isEmpty();
        assertThat(serverLog.errors).isEmpty();
    }

    @Test
    void eachFailureKindIsReportedOnceInTheServerLogWhileEveryEntryGetsItsFallback() {
        boundary.guardEntry(Fallback.QUERY, "query end", 1, () -> {
            throw new IllegalStateException("first");
        });
        boundary.guardEntry(Fallback.QUERY, "query end", 2, () -> {
            throw new IllegalStateException("second, same kind");
        });
        boundary.guardEntry(Fallback.QUERY, "query end", 3, () -> {
            throw new UnsupportedOperationException("another kind");
        });
        boundary.guardEntry(Fallback.TRANSACTION, "transaction end", 4, () -> {
            throw new IllegalStateException("same class, another callback");
        });

        assertThat(queryLog.errors).hasSize(4);
        assertThat(serverLog.errors)
                .containsExactly(
                        "Query log 'query end' failed with java.lang.IllegalStateException; the server continues and"
                                + " a fallback entry marks each affected entry in query.log: first",
                        "Query log 'query end' failed with java.lang.UnsupportedOperationException; the server"
                                + " continues and a fallback entry marks each affected entry in query.log: another kind",
                        "Query log 'transaction end' failed with java.lang.IllegalStateException; the server continues"
                                + " and a fallback entry marks each affected entry in query.log: same class, another"
                                + " callback");
        assertThat(serverLog.causes)
                .extracting(Throwable::getMessage)
                .containsExactly("first", "another kind", "same class, another callback");
    }

    @Test
    void aLinkageErrorIsIsolatedLikeAnException() {
        assertThatCode(() -> boundary.guardEntry(Fallback.QUERY, "query end", 5, () -> {
                    throw new NoSuchMethodError("ExecutingQuery.gone()");
                }))
                .doesNotThrowAnyException();

        assertThat(queryLog.errors)
                .containsExactly(
                        "Query log entry could not be written: id:5 - java.lang.NoSuchMethodError: ExecutingQuery.gone()");
    }

    @Test
    void aFallbackThatFailsItselfIsSwallowedAndCounted() {
        queryLog.failOnError = true;

        assertThatCode(() -> boundary.guardEntry(Fallback.QUERY, "query end", 6, () -> {
                    throw new IllegalStateException("renderer broke");
                }))
                .doesNotThrowAnyException();

        assertThat(boundary.fallbackEntriesLost()).isEqualTo(1);
        assertThat(serverLog.errors).hasSize(1);
    }

    @Test
    void theLogKeepsRunningAfterAFailure() {
        boundary.guardEntry(Fallback.QUERY, "query end", 7, () -> {
            throw new IllegalStateException("once");
        });
        boundary.guardEntry(
                Fallback.QUERY, "query end", 8, () -> queryLog.info(new RenderedEntry("the next entry", Map.of())));

        assertThat(queryLog.infos).containsExactly("the next entry");
    }

    @Test
    void aLifecycleFailureIsReportedOnceWithoutAFallbackEntry() {
        boundary.guard("init", () -> {
            throw new IllegalStateException("no monitor bus");
        });
        boundary.guard("init", () -> {
            throw new IllegalStateException("still none");
        });

        assertThat(queryLog.errors).isEmpty();
        assertThat(serverLog.errors)
                .containsExactly("Query log 'init' failed with java.lang.IllegalStateException; the server continues"
                        + " and a fallback entry marks each affected entry in query.log: no monitor bus");
    }

    @Test
    void aFailingTransactionEntryBecomesAFallbackEntryNamingTheTransactionIdInBothLayouts() {
        assertThatCode(() -> boundary.guardEntry(Fallback.TRANSACTION, "transaction end", 12, () -> {
                    throw new IllegalStateException("renderer broke");
                }))
                .doesNotThrowAnyException();

        assertThat(queryLog.errors)
                .containsExactly("Query log entry could not be written: transaction id:12"
                        + " - java.lang.IllegalStateException: renderer broke");
        assertThat(queryLog.errorFields)
                .containsExactly(Map.of(
                        "type",
                        "transaction",
                        "transactionId",
                        12L,
                        "failureReason",
                        "Query log entry could not be written: transaction id:12"
                                + " - java.lang.IllegalStateException: renderer broke"));
        assertThat(serverLog.errors).singleElement().asString().contains("'transaction end' failed");
    }

    private static final class RecordingQueryLog implements QueryLogWriter {
        final List<String> infos = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Map<String, Object>> errorFields = new ArrayList<>();
        boolean failOnError;

        @Override
        public void info(RenderedEntry entry) {
            infos.add(entry.line());
        }

        @Override
        public void error(RenderedEntry entry, Throwable cause) {
            if (failOnError) {
                throw new IllegalStateException("appender broke");
            }
            errors.add(entry.line());
            errorFields.add(entry.fields());
        }
    }

    private static final class RecordingServerLog implements ServerLog {
        final List<String> errors = new ArrayList<>();
        final List<Throwable> causes = new ArrayList<>();

        @Override
        public void error(String message, Throwable cause) {
            errors.add(message);
            causes.add(cause);
        }
    }
}
