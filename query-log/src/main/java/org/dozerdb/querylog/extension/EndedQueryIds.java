/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.extension;

import java.util.Arrays;

/**
 * The ring of the last ended query ids that de-duplicates end events: Neo4j 2026.10.1 reports a routed query
 * whose failure is raised in the Cypher engine's compile step twice (once from the router's end monitor, once from
 * the router's own catch block), and the query log must write one failed-query entry. Checked at every
 * {@code endSuccess} and {@code endFailure}; allocated once at {@code init()}; present on both release lines.
 *
 * <p>A fixed array scanned under one lock: a query id is a {@code long} Neo4j hands out from {@code 0} upwards, so
 * an empty slot holds {@code -1}, which no query ever has.
 */
final class EndedQueryIds {

    /** The capacity: ample for the few events that can arrive between a double report's two halves. */
    static final int CAPACITY = 1024;

    private static final long EMPTY = -1;

    private final long[] ring;
    private int next;

    EndedQueryIds(int capacity) {
        this.ring = new long[capacity];
        Arrays.fill(ring, EMPTY);
    }

    /** Records the end of a query; {@code false} when this id already ended within the ring's memory. */
    synchronized boolean markEnded(long queryId) {
        for (long ended : ring) {
            if (ended == queryId) {
                return false;
            }
        }
        ring[next] = queryId;
        next = (next + 1) % ring.length;
        return true;
    }
}
