/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.dozerdb.querylog.entry.TransactionEntry.Kind;
import org.junit.jupiter.api.Test;

/**
 * The JSON field map of a transaction entry: the keys the Operations Manual lists for {@code type: transaction}
 * ({@code event}, {@code transactionId}, {@code database}, {@code executingUser}, {@code authenticatedUser}) plus,
 * on a rollback caused by a failure, the failure's message under {@code failureReason}, the key a failed-query entry
 * uses; an absent field is absent rather than {@code null}. The plain-only segments (open time, source, annotation
 * data) stay out of the map; the stack trace of a failed rollback is the layout's own {@code stacktrace}.
 */
class TransactionFieldMapRendererTest {

    private static TransactionEntry.Builder committed() {
        return TransactionEntry.builder()
                .kind(Kind.COMMIT)
                .transactionId(12)
                .openMillis(41)
                .source(TransactionPlainTextRendererTest.BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .authenticatedUser("admin")
                .annotationData(Map.of("app", "cypher-shell_v2026.10"));
    }

    @Test
    void aCommitEntryCarriesTheManualsFiveKeysAndTheType() {
        Map<String, Object> fields =
                TransactionFieldMapRenderer.render(committed().build());

        assertThat(fields)
                .containsEntry("type", "transaction")
                .containsEntry("event", "commit")
                .containsEntry("transactionId", 12L)
                .containsEntry("database", "neo4j")
                .containsEntry("executingUser", "neo4j")
                .containsEntry("authenticatedUser", "admin")
                .hasSize(6);
    }

    @Test
    void theEventNamesTheKindAsTheManualSpellsIt() {
        assertThat(TransactionFieldMapRenderer.render(
                        committed().kind(Kind.START).build()))
                .containsEntry("event", "start");
        assertThat(TransactionFieldMapRenderer.render(
                        committed().kind(Kind.ROLLBACK).build()))
                .containsEntry("event", "rollback");
    }

    @Test
    void aRollbackCausedByAFailureCarriesItsReasonUnderTheKeyAQueryEntryUses() {
        TransactionEntry entry = committed()
                .kind(Kind.ROLLBACK)
                .failureReason("listener refused the commit")
                .failureCause(new IllegalStateException("listener refused the commit"))
                .build();

        assertThat(TransactionFieldMapRenderer.render(entry))
                .containsEntry("event", "rollback")
                .containsEntry("failureReason", "listener refused the commit")
                .hasSize(7);
    }

    @Test
    void aRollbackTheClientAskedForAndAFailureWithoutAMessageCarryNoReasonKey() {
        assertThat(TransactionFieldMapRenderer.render(
                        committed().kind(Kind.ROLLBACK).build()))
                .doesNotContainKey("failureReason");
        assertThat(TransactionFieldMapRenderer.render(committed()
                        .kind(Kind.ROLLBACK)
                        .failureCause(new IllegalStateException())
                        .build()))
                .doesNotContainKey("failureReason");
    }

    @Test
    void anAbsentFieldIsAbsentNotNull() {
        TransactionEntry entry = committed()
                .kind(Kind.ROLLBACK)
                .executingUser(null)
                .authenticatedUser(null)
                .failureCause(new IllegalStateException("listener refused the commit"))
                .build();

        Map<String, Object> fields = TransactionFieldMapRenderer.render(entry);

        assertThat(fields)
                .containsOnlyKeys("type", "event", "transactionId", "database")
                .doesNotContainValue(null);
    }
}
