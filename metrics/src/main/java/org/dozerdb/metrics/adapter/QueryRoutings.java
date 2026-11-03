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
 * Where Neo4j's query router sends the queries it routes, as the query routing collector hears of it: the router
 * announces every routing decision on the global monitor bus, once per query it routes. The collector listens while
 * it is registered and gives the listener back when it is retired.
 */
public interface QueryRoutings {

    /** Delivers every routing decision, on the thread that routes the query, from now on. */
    void observeRoutings(QueryRoutingEvents events);

    /** Stops listening. */
    void stopObservingRoutings();

    /** The three places a query can be routed to. */
    interface QueryRoutingEvents {

        /** The query runs on a database of this server. */
        void routedLocal();

        /** The query was sent to another member of the same cluster. */
        void routedRemoteInternal();

        /** The query was sent to a server outside the cluster (a remote database alias). */
        void routedRemoteExternal();
    }
}
