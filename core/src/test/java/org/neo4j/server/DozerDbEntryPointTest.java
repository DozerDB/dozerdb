/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.neo4j.server.startup.EntryPoint;

/**
 * The server entry point is chosen by the lowest priority value among the service providers on the
 * classpath. Launchers that use a {@code lib/*} wildcard (Windows, and the stock Unix launcher when the
 * plugin JAR is dropped into {@code lib}) give no classpath ordering guarantee, so DozerDB must win on
 * priority alone rather than on ordering.
 */
class DozerDbEntryPointTest {

    @Test
    void dozerDbEntryPointOutranksCommunityEntryPoint() {
        assertThat(new DozerDbEntryPoint().getPriority())
                .as("DozerDB must rank ahead of the community entry point regardless of classpath order")
                .isLessThan(new Neo4jCommunity().getPriority());
    }

    @Test
    void serviceLoaderSelectsDozerDbEntryPoint() {
        assertThat(EntryPoint.serviceloadEntryPoint()).isEqualTo(DozerDbEntryPoint.class);
    }
}
