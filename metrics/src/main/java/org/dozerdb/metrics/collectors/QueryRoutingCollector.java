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

import java.util.function.LongConsumer;
import org.dozerdb.metrics.adapter.QueryRoutings;
import org.dozerdb.metrics.adapter.QueryRoutings.QueryRoutingEvents;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Query routing metrics (Operations Manual 5, "Query routing metrics"): the {@code routing.query.count.*} rows,
 * global, one event counter per place Neo4j's query router can send a query. Each routing decision is one increment
 * on the routing thread, the only work metrics add there; the listener is removed when the collector is retired. On a single
 * instance every routed query is local: there is no other cluster member, and Community has no remote database
 * alias, so the two remote counters are registered and stay at zero.
 */
public final class QueryRoutingCollector implements Collector {

    public static final String NAME = "routing.query";

    private final QueryRoutings routings;

    public QueryRoutingCollector(QueryRoutings routings) {
        this.routings = routings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        routings.observeRoutings(new Counts(
                metrics.eventCounter("routing.query.count.local"),
                metrics.eventCounter("routing.query.count.remote_internal"),
                metrics.eventCounter("routing.query.count.remote_external")));
        metrics.onRetire(routings::stopObservingRoutings);
    }

    /** One event counter per routing destination; each routed query is one increment. */
    private record Counts(LongConsumer local, LongConsumer remoteInternal, LongConsumer remoteExternal)
            implements QueryRoutingEvents {

        @Override
        public void routedLocal() {
            local.accept(1);
        }

        @Override
        public void routedRemoteInternal() {
            remoteInternal.accept(1);
        }

        @Override
        public void routedRemoteExternal() {
            remoteExternal.accept(1);
        }
    }
}
