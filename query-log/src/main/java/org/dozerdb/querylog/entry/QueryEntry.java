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
 * One query entry of the query log: the immutable, Java-only record the upstream adapter copies from Neo4j's query
 * snapshot once it knows the entry will be written, and the renderers turn into a line or a field map. Every
 * field is already in its final form: the parameters as their single-line Cypher map, the source as Neo4j's
 * connection details, the database {@code <none>} when the query never reached one, the plan as its table text, the
 * cache key hash as its eight hex digits. A {@code null} field is one the entry does not carry: the renderers leave
 * its segment or key out and never write the text {@code null} (the one documented exception is the runtime, whose
 * absence the plain line spells {@code runtime=null}, as the manual's start entry shows it).
 *
 * <p>The three kinds are the glossary's start entry, success entry and failed-query entry; a start entry carries
 * zeroed timing, allocation and page figures and no runtime. The detail level is the one the entry was written
 * under: the {@code id:} prefix and the JSON {@code id} appear at {@code VERBOSE} only.
 *
 * @param kind start entry, success entry or failed-query entry
 * @param detailLevel the detail level the entry was written under, {@code INFO} or {@code VERBOSE}
 * @param queryId the query id ({@code ExecutingQuery.internalQueryId()}), the {@code id:} prefix and JSON {@code id}
 * @param transactionId the outer transaction's sequence number, {@code -1} before the query is bound to a transaction,
 *     {@code 0} on an early start entry
 * @param elapsedMillis wall time from the query's start to its end
 * @param planningMillis time spent compiling
 * @param cpuMillis CPU time, present only when Neo4j tracks it ({@code db.track_query_cpu_time}); JSON only
 * @param waitingMillis time spent waiting on locks or other queries
 * @param allocatedBytes the heap high-water mark of the query
 * @param pageHits page cache hits of the query's transactions
 * @param pageFaults page cache faults of the query's transactions
 * @param source Neo4j's connection details of the client ({@code bolt-session\tbolt\t...>}, {@code embedded-session\t})
 * @param database the database name or {@code <none>}
 * @param executingUser the executing user, the plain line's one username
 * @param authenticatedUser the authenticated user, which differs from the executing user under impersonation; JSON only
 * @param queryText the query text verbatim, line breaks included
 * @param parameters the parameters rendered as a Cypher map, {@code {}} when there are none, {@code null} when
 *     parameter logging is off (the segment is dropped)
 * @param runtime Community's lower-case runtime name, or {@code null} when the query never reached a runtime
 * @param annotationData the transaction's metadata as plain Java values
 * @param queryLanguage the Cypher version the query ran under ({@code CYPHER 5}, {@code CYPHER 25}); JSON only,
 *     {@code null} on a line without the accessor (Neo4j 5.26)
 * @param executionPlanCacheKeyHash the plan cache key hash as eight upper-case hex digits, {@code 00000000} at start;
 *     JSON only
 * @param queryPlan the plan table, the text of the plan description as Neo4j's {@code EXPLAIN} renders it; present only
 *     under {@code plan_description_enabled} on an end entry whose query compiled
 * @param failureReason the failure's message for a failed-query entry; {@code null} when Neo4j reported none
 * @param errorInfo the failure's GQLSTATUS tree as plain maps and strings; JSON only
 * @param failureCause the failure itself when Neo4j reported one, for the stack trace Log4j writes after the line
 */
public record QueryEntry(
        Kind kind,
        DetailLevel detailLevel,
        long queryId,
        long transactionId,
        long elapsedMillis,
        long planningMillis,
        Long cpuMillis,
        long waitingMillis,
        long allocatedBytes,
        long pageHits,
        long pageFaults,
        String source,
        String database,
        String executingUser,
        String authenticatedUser,
        String queryText,
        String parameters,
        String runtime,
        Map<String, Object> annotationData,
        String queryLanguage,
        String executionPlanCacheKeyHash,
        String queryPlan,
        String failureReason,
        Map<String, Object> errorInfo,
        Throwable failureCause) {

    /** The database written for a query that failed before it was bound to a database. */
    public static final String NO_DATABASE = "<none>";

    /**
     * The transaction id of an early start entry, written before the query has reached a transaction. Distinct from
     * {@code -1}, Neo4j's own value on a query that ended without ever being bound.
     */
    public static final long UNBOUND_TRANSACTION_ID = 0;

    /** The cache key hash of a query that has not been planned, the manual's value for a start entry. */
    public static final String UNPLANNED_CACHE_KEY_HASH = "00000000";

    /** The kind of query entry; the JSON {@code event} values are {@code start}, {@code success} and {@code fail}. */
    public enum Kind {
        START,
        SUCCESS,
        FAIL
    }

    public QueryEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detailLevel, "detailLevel");
        // Not Map.copyOf: a Bolt client may send a null metadata value, which renders as <null>.
        annotationData = annotationData == null ? Map.of() : unmodifiableCopy(annotationData);
        errorInfo = errorInfo == null ? null : unmodifiableCopy(errorInfo);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether this is a failed-query entry (level {@code ERROR}) rather than a start or success entry ({@code INFO}). */
    public boolean failed() {
        return kind == Kind.FAIL;
    }

    private static Map<String, Object> unmodifiableCopy(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * Builds an entry field by field, so that the adapter and the tests name what they set. Defaults: a success entry
     * at {@code VERBOSE}, every number zero, the transaction id {@code -1} (unbound), empty annotation data and every
     * other field absent.
     */
    public static final class Builder {
        private Kind kind = Kind.SUCCESS;
        private DetailLevel detailLevel = DetailLevel.VERBOSE;
        private long queryId;
        private long transactionId = -1;
        private long elapsedMillis;
        private long planningMillis;
        private Long cpuMillis;
        private long waitingMillis;
        private long allocatedBytes;
        private long pageHits;
        private long pageFaults;
        private String source;
        private String database;
        private String executingUser;
        private String authenticatedUser;
        private String queryText;
        private String parameters;
        private String runtime;
        private Map<String, Object> annotationData = Map.of();
        private String queryLanguage;
        private String executionPlanCacheKeyHash;
        private String queryPlan;
        private String failureReason;
        private Map<String, Object> errorInfo;
        private Throwable failureCause;

        private Builder() {}

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder detailLevel(DetailLevel detailLevel) {
            this.detailLevel = detailLevel;
            return this;
        }

        public Builder queryId(long queryId) {
            this.queryId = queryId;
            return this;
        }

        public Builder transactionId(long transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder elapsedMillis(long elapsedMillis) {
            this.elapsedMillis = elapsedMillis;
            return this;
        }

        public Builder planningMillis(long planningMillis) {
            this.planningMillis = planningMillis;
            return this;
        }

        public Builder cpuMillis(Long cpuMillis) {
            this.cpuMillis = cpuMillis;
            return this;
        }

        public Builder waitingMillis(long waitingMillis) {
            this.waitingMillis = waitingMillis;
            return this;
        }

        public Builder allocatedBytes(long allocatedBytes) {
            this.allocatedBytes = allocatedBytes;
            return this;
        }

        public Builder pageHits(long pageHits) {
            this.pageHits = pageHits;
            return this;
        }

        public Builder pageFaults(long pageFaults) {
            this.pageFaults = pageFaults;
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

        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        public Builder parameters(String parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder runtime(String runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder annotationData(Map<String, Object> annotationData) {
            this.annotationData = annotationData;
            return this;
        }

        public Builder queryLanguage(String queryLanguage) {
            this.queryLanguage = queryLanguage;
            return this;
        }

        public Builder executionPlanCacheKeyHash(String executionPlanCacheKeyHash) {
            this.executionPlanCacheKeyHash = executionPlanCacheKeyHash;
            return this;
        }

        public Builder queryPlan(String queryPlan) {
            this.queryPlan = queryPlan;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder errorInfo(Map<String, Object> errorInfo) {
            this.errorInfo = errorInfo;
            return this;
        }

        public Builder failureCause(Throwable failureCause) {
            this.failureCause = failureCause;
            return this;
        }

        public QueryEntry build() {
            return new QueryEntry(
                    kind,
                    detailLevel,
                    queryId,
                    transactionId,
                    elapsedMillis,
                    planningMillis,
                    cpuMillis,
                    waitingMillis,
                    allocatedBytes,
                    pageHits,
                    pageFaults,
                    source,
                    database,
                    executingUser,
                    authenticatedUser,
                    queryText,
                    parameters,
                    runtime,
                    annotationData,
                    queryLanguage,
                    executionPlanCacheKeyHash,
                    queryPlan,
                    failureReason,
                    errorInfo,
                    failureCause);
        }
    }
}
