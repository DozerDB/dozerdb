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

import java.util.LinkedHashMap;
import java.util.Map;
import org.dozerdb.querylog.entry.TransactionEntry.Kind;
import org.junit.jupiter.api.Test;

/**
 * The plain-text shape of a transaction entry, which the manual leaves undocumented and DozerDB defines: the event
 * marker, then {@code transaction id:N - N ms - <source>\t<database> - <user> -
 * <annotations>}, a rollback caused by a failure closing with the reason. Every example line of the field inventory is
 * rendered too, by {@code FieldInventoryTest}.
 */
class TransactionPlainTextRendererTest {

    static final String BOLT_SOURCE =
            "bolt-session\tbolt\tneo4j-cypher-shell/v2026.10.0\t\tclient/127.0.0.1:53864\tserver/127.0.0.1:7687>";

    private static TransactionEntry.Builder cypherShellOnBolt() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("type", "system");
        annotations.put("app", "cypher-shell_v2026.10");
        return TransactionEntry.builder()
                .transactionId(12)
                .openMillis(41)
                .source(BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .authenticatedUser("neo4j")
                .annotationData(annotations);
    }

    @Test
    void aCommitEntryIsTheRoadmapsLineWithTheAnnotationKeysSorted() {
        TransactionEntry entry = cypherShellOnBolt().kind(Kind.COMMIT).build();

        assertThat(TransactionPlainTextRenderer.render(entry))
                .isEqualTo("Transaction committed: transaction id:12 - 41 ms - " + BOLT_SOURCE
                        + "\tneo4j - neo4j - {app: 'cypher-shell_v2026.10', type: 'system'}");
    }

    @Test
    void aStartEntryHasZeroOpenTimeAndTheMetadataNeo4jHasNotYetAttached() {
        TransactionEntry entry = cypherShellOnBolt()
                .kind(Kind.START)
                .openMillis(0)
                .annotationData(Map.of())
                .build();

        assertThat(TransactionPlainTextRenderer.render(entry))
                .isEqualTo("Transaction started: transaction id:12 - 0 ms - " + BOLT_SOURCE + "\tneo4j - neo4j - {}");
    }

    @Test
    void aRollbackWithoutAFailureIsAnOrdinaryEndEntry() {
        TransactionEntry entry =
                cypherShellOnBolt().kind(Kind.ROLLBACK).openMillis(7).build();

        assertThat(TransactionPlainTextRenderer.render(entry))
                .isEqualTo("Transaction rolled back: transaction id:12 - 7 ms - " + BOLT_SOURCE
                        + "\tneo4j - neo4j - {app: 'cypher-shell_v2026.10', type: 'system'}");
        assertThat(entry.failed()).isFalse();
    }

    @Test
    void aRollbackCausedByAFailureClosesWithTheReasonAndIsAFailedEntry() {
        TransactionEntry entry = cypherShellOnBolt()
                .kind(Kind.ROLLBACK)
                .openMillis(7)
                .failureReason("Node(0) already exists with label `Person` and property `id` = 1")
                .failureCause(new IllegalStateException("not part of the line"))
                .build();

        assertThat(TransactionPlainTextRenderer.render(entry))
                .isEqualTo("Transaction rolled back: transaction id:12 - 7 ms - " + BOLT_SOURCE
                        + "\tneo4j - neo4j - {app: 'cypher-shell_v2026.10', type: 'system'}"
                        + " - Node(0) already exists with label `Person` and property `id` = 1");
        assertThat(entry.failed()).isTrue();
    }

    @Test
    void anEmbeddedTransactionWithNoUserLeavesTheSegmentsEmptyAndKeepsTheSourcesOwnTab() {
        TransactionEntry entry = TransactionEntry.builder()
                .kind(Kind.COMMIT)
                .transactionId(3)
                .openMillis(2)
                .source("embedded-session\t")
                .database("system")
                .executingUser("")
                .authenticatedUser("")
                .build();

        assertThat(TransactionPlainTextRenderer.render(entry))
                .isEqualTo("Transaction committed: transaction id:3 - 2 ms - embedded-session\t\tsystem -  - {}");
    }
}
