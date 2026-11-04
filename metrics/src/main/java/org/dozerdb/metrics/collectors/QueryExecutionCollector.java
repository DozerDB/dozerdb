/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.collectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import org.dozerdb.metrics.adapter.DatabaseDependencies;
import org.dozerdb.metrics.adapter.QueryExecutions;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Query execution metrics (Operations Manual 2026.10, "Query execution metrics"): the {@code db.query.execution.*}
 * rows of one database. Nothing in Neo4j counts queries, so this collector listens to the end of every query of the
 * database while it is registered: a success increments the success counter and feeds the latency histogram with the
 * query's elapsed milliseconds, a failure increments the failure counter, and the same happens in the series of the
 * Cypher runtime that executed the query, for the runtimes the manual documents. One instance per started database,
 * built from that database's dependencies.
 */
public final class QueryExecutionCollector implements Collector {

    public static final String GROUP = "db.query.execution";

    /**
     * The runtimes the manual documents a series for, by the name Neo4j gives the runtime
     * ({@code RuntimeName.asString()}). Community has only the slotted runtime among them: a query that asks for the
     * pipelined or the parallel runtime falls back to slotted, so those two series are registered and stay at zero,
     * as every documented counter that only an Enterprise mechanism drives.
     */
    static final List<String> DOCUMENTED_RUNTIMES = List.of("parallel", "pipelined", "slotted");

    private final DatabaseDependencies database;

    public QueryExecutionCollector(DatabaseDependencies database) {
        this.database = database;
    }

    @Override
    public String name() {
        return Collector.nameForDatabase(GROUP, database.databaseName());
    }

    @Override
    public void register(MetricRegistration metrics) {
        Series all = Series.register(metrics, GROUP);
        Map<String, Series> byRuntime = new LinkedHashMap<>();
        for (String runtime : DOCUMENTED_RUNTIMES) {
            byRuntime.put(runtime, Series.register(metrics, GROUP + "." + runtime));
        }
        QueryExecutions queries = database.queryExecutions();
        queries.observeQueries(new QueryExecutions.QueryExecutionEvents() {
            @Override
            public void querySucceeded(String runtime, long elapsedMillis) {
                all.succeeded(elapsedMillis);
                Series series = byRuntime.get(runtime);
                if (series != null) {
                    series.succeeded(elapsedMillis);
                }
            }

            @Override
            public void queryFailed(String runtime) {
                all.failed();
                Series series = byRuntime.get(runtime);
                if (series != null) {
                    series.failed();
                }
            }
        });
        metrics.onRetire(queries::stopObservingQueries);
    }

    /** The success count, failure count and latency histogram of one series (overall or one runtime). */
    private record Series(LongConsumer success, LongConsumer failure, LongConsumer latencyMillis) {

        static Series register(MetricRegistration metrics, String prefix) {
            return new Series(
                    metrics.eventCounter(prefix + ".success"),
                    metrics.eventCounter(prefix + ".failure"),
                    metrics.histogram(prefix + ".latency.millis"));
        }

        void succeeded(long elapsedMillis) {
            success.accept(1);
            latencyMillis.accept(elapsedMillis);
        }

        void failed() {
            failure.accept(1);
        }
    }
}
