/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

/**
 * The query executions of one database as the query execution collector hears of them: Neo4j announces the end of
 * every query on the database's own monitor bus, once per query, with the Cypher runtime that executed it and the
 * time it took. The collector listens while it is registered and gives the listener back when it is retired.
 */
public interface QueryExecutions {

    /** Delivers the end of every query of the database, on the thread that ends it, from now on. */
    void observeQueries(QueryExecutionEvents events);

    /** Stops listening. */
    void stopObservingQueries();

    /** What the collector learns about a query that has ended. */
    interface QueryExecutionEvents {

        /**
         * A query ran to completion.
         *
         * @param runtime the lower-case name of the Cypher runtime that executed it ({@code slotted},
         *     {@code pipelined}, {@code parallel}, {@code interpreted}, {@code schema}, {@code system}, ...)
         * @param elapsedMillis the time from the query's start to its end
         */
        void querySucceeded(String runtime, long elapsedMillis);

        /**
         * A query failed.
         *
         * @param runtime the lower-case name of the Cypher runtime that executed it, or an empty string when it failed
         *     before it was compiled
         */
        void queryFailed(String runtime);
    }
}
