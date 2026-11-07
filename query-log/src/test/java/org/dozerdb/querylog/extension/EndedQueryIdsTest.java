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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The ring of the last 1024 ended query ids accepts an id once; a second end of the same query (Neo4j 2026.10.1
 * reports a routed query's compile failure twice) is refused until the id has been pushed out by 1024 newer ones.
 */
class EndedQueryIdsTest {

    @Test
    void anIdIsAcceptedOnceAndRefusedWhenReportedAgain() {
        EndedQueryIds ended = new EndedQueryIds(1024);

        assertThat(ended.markEnded(42)).isTrue();
        assertThat(ended.markEnded(42)).isFalse();
        assertThat(ended.markEnded(43)).isTrue();
    }

    @Test
    void anIdFallsOutOfTheRingAfterItsCapacityOfNewerIds() {
        EndedQueryIds ended = new EndedQueryIds(4);
        ended.markEnded(1);
        ended.markEnded(2);
        ended.markEnded(3);
        ended.markEnded(4);

        assertThat(ended.markEnded(1)).as("still among the last four").isFalse();
        ended.markEnded(5);
        assertThat(ended.markEnded(1)).as("pushed out by the fifth id").isTrue();
        assertThat(ended.markEnded(2)).as("pushed out by id 1's return").isTrue();
    }

    @Test
    void theFirstQueryIdNeo4jAssignsIsNotMistakenForAnEmptySlot() {
        EndedQueryIds ended = new EndedQueryIds(2);

        assertThat(ended.markEnded(0)).isTrue();
        assertThat(ended.markEnded(0)).isFalse();
    }
}
