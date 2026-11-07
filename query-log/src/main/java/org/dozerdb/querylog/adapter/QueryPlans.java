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

import java.util.function.Supplier;
import org.neo4j.graphdb.ExecutionPlanDescription;
import org.neo4j.kernel.api.query.QuerySnapshot;

/**
 * The plan table of a query entry, as {@code db.logs.query.plan_description_enabled} asks for it: the text of the
 * {@link ExecutionPlanDescription} the query's plan description supplier hands out, which is the text Neo4j's own
 * {@code EXPLAIN} shows a client (the Cypher version, planner and runtime header lines, the tree table with one row
 * per operator and the {@code Total database accesses} summary). Neo4j renders it; nothing here draws a table, so
 * the text is the same in every release line that has the supplier.
 *
 * <p>The supplier is read from the end snapshot and not from the plan-computed monitor event, because only the
 * snapshot exists in every Neo4j version the module targets. Neo4j sets it once the outermost query has compiled and
 * never clears it on failure, so a query that failed while running still has its table; it is {@code null} for a query
 * that failed before or during compilation, for the parent query of a composite database and between a retry and its
 * recompilation, and then the entry carries no plan. The description the supplier builds is always the {@code EXPLAIN}
 * shape: it is derived from the logical plan alone and never from the result, so no profile figure can appear and
 * reading it never touches the query's transaction. It is built on each call, which is why the adapter reads it only
 * when the setting is on.
 */
final class QueryPlans {

    private QueryPlans() {}

    /** The plan text of the query the snapshot describes, or {@code null} when the query has no plan description. */
    static String tableOf(QuerySnapshot snapshot) {
        Supplier<ExecutionPlanDescription> supplier = snapshot.queryPlanSupplier();
        if (supplier == null) {
            return null;
        }
        ExecutionPlanDescription plan = supplier.get();
        return plan == null ? null : plan.toString();
    }
}
