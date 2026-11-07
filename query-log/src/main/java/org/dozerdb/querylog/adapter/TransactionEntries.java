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

import org.dozerdb.querylog.entry.TransactionEntry;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.kernel.api.KernelTransaction;

/**
 * Copies a kernel transaction into a {@link TransactionEntry}, every field converted here: the sequence number that
 * the transaction's query entries carry as their {@code transaction id}, the connection to Neo4j's connection
 * details, the security context to its two usernames, the metadata to a plain map. Every accessor is one Neo4j keeps
 * readable while the monitor runs at commit and rollback (the security context deliberately so, as the kernel's own
 * comment says) and clears only afterwards.
 *
 * <p>A start entry copies the same fields with no open time and the metadata as it stands, which for a Bolt, HTTP or
 * embedded transaction is empty because clients attach their metadata after the kernel has reported the start; the
 * end entry carries the metadata the transaction ended with. A rollback that Neo4j reports with a failure carries the
 * failure's message as its reason and the failure itself for the stack trace.
 */
final class TransactionEntries {

    private TransactionEntries() {}

    static TransactionEntry started(KernelTransaction tx) {
        return common(tx).kind(TransactionEntry.Kind.START).build();
    }

    static TransactionEntry committed(KernelTransaction tx, long openMillis) {
        return common(tx)
                .kind(TransactionEntry.Kind.COMMIT)
                .openMillis(openMillis)
                .build();
    }

    static TransactionEntry rolledBack(KernelTransaction tx, long openMillis, Throwable failure) {
        return common(tx)
                .kind(TransactionEntry.Kind.ROLLBACK)
                .openMillis(openMillis)
                .failureReason(failure == null ? null : failure.getMessage())
                .failureCause(failure)
                .build();
    }

    private static TransactionEntry.Builder common(KernelTransaction tx) {
        ClientConnectionInfo connection = tx.clientInfo();
        AuthSubject subject = tx.securityContext().subject();
        return TransactionEntry.builder()
                .transactionId(tx.getTransactionSequenceNumber())
                .source(connection == null ? null : connection.asConnectionDetails())
                .database(tx.getDatabaseName())
                .executingUser(subject.executingUser())
                .authenticatedUser(subject.authenticatedUser())
                .annotationData(tx.getMetaData());
    }
}
