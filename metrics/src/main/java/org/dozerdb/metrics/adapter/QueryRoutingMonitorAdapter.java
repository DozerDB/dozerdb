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

import org.neo4j.kernel.impl.query.QueryRoutingMonitor;

/**
 * Adapts Neo4j's {@link QueryRoutingMonitor} on the global monitor bus ({@link Neo4jDependencies#monitors()},
 * resolved when the listener is added) to {@link QueryRoutings}. The query router ({@code QueryRouterImpl}, created by
 * {@code CommunityQueryRouterBootstrap} with a monitor from the global bus) fires it once per query it routes, after
 * planning and before the query runs; the Fabric executor ({@code FabricServicesBootstrap}) fires the same monitor
 * for the fragments of a composite database query, and composite databases are an Enterprise feature. Only queries that reach the router are routed: those of Bolt connections and of the Query API (which drives a local Bolt channel), not
 * embedded ones ({@code TransactionImpl.execute} goes to the database's Cypher engine directly).
 */
public final class QueryRoutingMonitorAdapter implements QueryRoutings {

    private final Neo4jDependencies dependencies;
    private volatile Listener listener;

    private QueryRoutingMonitorAdapter(Neo4jDependencies dependencies) {
        this.dependencies = dependencies;
    }

    public static QueryRoutings of(Neo4jDependencies dependencies) {
        return new QueryRoutingMonitorAdapter(dependencies);
    }

    @Override
    public void observeRoutings(QueryRoutingEvents events) {
        Listener added = new Listener(events);
        dependencies.monitors().addMonitorListener(added);
        listener = added;
    }

    @Override
    public void stopObservingRoutings() {
        Listener removed = listener;
        if (removed != null) {
            listener = null;
            dependencies.monitors().removeMonitorListener(removed);
        }
    }

    private record Listener(QueryRoutingEvents events) implements QueryRoutingMonitor {

        @Override
        public void queryRoutedLocal() {
            events.routedLocal();
        }

        @Override
        public void queryRoutedRemoteInternal() {
            events.routedRemoteInternal();
        }

        @Override
        public void queryRoutedRemoteExternal() {
            events.routedRemoteExternal();
        }
    }
}
