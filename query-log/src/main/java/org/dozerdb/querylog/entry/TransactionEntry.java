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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One transaction entry of the query log: the immutable, Java-only record the upstream adapter copies from Neo4j's
 * kernel transaction once it knows the entry will be written, and the renderers turn into a line or a field map. The
 * glossary's three kinds are a transaction's start, commit and rollback; every kernel transaction the monitor reports
 * is a candidate, the system database's and Neo4j's own internal ones included, because an audit record that filters
 * transactions by origin would have to explain every omission. A {@code null} field is one the entry does not carry:
 * the renderers leave its segment or key out and never write the text {@code null}.
 *
 * <p>The open time is how long the transaction had been open when it committed or rolled back, the figure
 * {@code db.logs.query.transaction.threshold} filters on; a start entry carries {@code 0}. The transaction id is the
 * sequence number the query entries run inside the transaction carry as their {@code transaction id}, so the two kinds
 * of entry join on it. Annotation data is read at the end event: Neo4j's clients set a transaction's metadata after
 * the kernel has reported its start, so a start entry shows {@code {}}.
 *
 * <p>A rollback that Neo4j reports with a throwable is a failed transaction: the entry carries the throwable's message
 * as its reason and the throwable itself for the stack trace, and is written at {@code ERROR} like a failed-query
 * entry, whatever the threshold. A rollback without one is an ordinary end that the threshold applies to.
 *
 * @param kind start, commit or rollback
 * @param transactionId the kernel transaction's sequence number, the {@code transaction id} of its query entries
 * @param openMillis how long the transaction had been open at commit or rollback, {@code 0} at start
 * @param source Neo4j's connection details of the client ({@code bolt-session\tbolt\t...>}, {@code embedded-session\t})
 * @param database the database the transaction ran on
 * @param executingUser the executing user, the plain line's one username
 * @param authenticatedUser the authenticated user, which differs from the executing user under impersonation; JSON only
 * @param annotationData the transaction's metadata as plain Java values; plain text only
 * @param failureReason the message of the failure that caused a rollback; {@code null} when there was none
 * @param failureCause the failure itself, for the stack trace Log4j writes after the line
 */
public record TransactionEntry(
        Kind kind,
        long transactionId,
        long openMillis,
        String source,
        String database,
        String executingUser,
        String authenticatedUser,
        Map<String, Object> annotationData,
        String failureReason,
        Throwable failureCause) {

    /** The kind of transaction entry; the JSON {@code event} values are {@code start}, {@code commit} and {@code rollback}. */
    public enum Kind {
        START,
        COMMIT,
        ROLLBACK
    }

    public TransactionEntry {
        Objects.requireNonNull(kind, "kind");
        // Not Map.copyOf: a Bolt client may send a null metadata value, which renders as <null>.
        annotationData =
                annotationData == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(annotationData));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether this is a rollback caused by a failure (level {@code ERROR}) rather than an ordinary entry ({@code INFO}). */
    public boolean failed() {
        return kind == Kind.ROLLBACK && failureCause != null;
    }

    /**
     * Builds an entry field by field, so that the adapter and the tests name what they set. Defaults: a commit entry,
     * every number zero, empty annotation data and every other field absent.
     */
    public static final class Builder {
        private Kind kind = Kind.COMMIT;
        private long transactionId;
        private long openMillis;
        private String source;
        private String database;
        private String executingUser;
        private String authenticatedUser;
        private Map<String, Object> annotationData = Map.of();
        private String failureReason;
        private Throwable failureCause;

        private Builder() {}

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder transactionId(long transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder openMillis(long openMillis) {
            this.openMillis = openMillis;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder executingUser(String executingUser) {
            this.executingUser = executingUser;
            return this;
        }

        public Builder authenticatedUser(String authenticatedUser) {
            this.authenticatedUser = authenticatedUser;
            return this;
        }

        public Builder annotationData(Map<String, Object> annotationData) {
            this.annotationData = annotationData;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder failureCause(Throwable failureCause) {
            this.failureCause = failureCause;
            return this;
        }

        public TransactionEntry build() {
            return new TransactionEntry(
                    kind,
                    transactionId,
                    openMillis,
                    source,
                    database,
                    executingUser,
                    authenticatedUser,
                    annotationData,
                    failureReason,
                    failureCause);
        }
    }
}
