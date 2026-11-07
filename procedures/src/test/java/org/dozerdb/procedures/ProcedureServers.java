/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.procedures;

import java.util.function.Consumer;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;

/**
 * Starts an in-process DozerDB for the procedure tests through core's builder, so that the procedures module is
 * active exactly as it is in a packaged server: discovered through Java service lookup, nothing registered by hand.
 * The web server is off and Bolt is off unless asked for, so the tests run beside a Neo4j installed on the machine.
 */
public final class ProcedureServers {

    private ProcedureServers() {}

    /** A server with no network connector, driven through its embedded API. */
    public static Neo4j start(Consumer<Neo4jBuilder> settings) {
        Neo4jBuilder builder =
                DozerDbBuilders.newInProcessBuilder().withDisabledServer().withConfig(BoltConnector.enabled, false);
        settings.accept(builder);
        return builder.build();
    }

    public static Neo4j start() {
        return start(builder -> {});
    }

    /** A server whose Bolt connector (and the routing connector Neo4j starts beside it) listen on free loopback ports. */
    public static Neo4j startWithBolt() {
        return start(builder -> builder.withConfig(BoltConnector.enabled, true)
                .withConfig(BoltConnector.listen_address, new SocketAddress("localhost", 0))
                .withConfig(GraphDatabaseSettings.routing_listen_address, new SocketAddress("localhost", 0)));
    }
}
