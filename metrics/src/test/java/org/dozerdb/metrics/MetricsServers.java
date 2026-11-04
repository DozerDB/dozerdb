/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;

/** Starts an in-process DozerDB with no network connectors and the given metrics settings. */
public final class MetricsServers {

    private MetricsServers() {}

    public static Neo4j start(Consumer<Neo4jBuilder> settings) {
        Neo4jBuilder builder =
                DozerDbBuilders.newInProcessBuilder().withDisabledServer().withConfig(BoltConnector.enabled, false);
        settings.accept(builder);
        return builder.build();
    }

    /**
     * Starts a server whose Bolt connector listens on a free loopback port ({@code Neo4j.boltURI()} names it), for
     * tests that drive the server with the Neo4j Java driver. The routing connector Neo4j starts beside Bolt
     * ({@code server.routing.listen_address}, port 7688 by default) is moved to a free port too, so that two servers
     * with Bolt can run in one JVM and none collides with a Neo4j installed on the machine.
     */
    public static Neo4j startWithBolt(Consumer<Neo4jBuilder> settings) {
        return start(builder -> {
            builder.withConfig(BoltConnector.enabled, true)
                    .withConfig(BoltConnector.listen_address, new SocketAddress("localhost", 0))
                    .withConfig(GraphDatabaseSettings.routing_listen_address, new SocketAddress("localhost", 0));
            settings.accept(builder);
        });
    }

    /**
     * Starts a server with the HTTP connector enabled on a free loopback port (the harness's default), so the web
     * server and the pools and threads that exist only with it are there; Bolt stays disabled unless
     * {@code settings} enables it.
     */
    public static Neo4j startWithWebServer(Consumer<Neo4jBuilder> settings) {
        Neo4jBuilder builder = DozerDbBuilders.newInProcessBuilder().withConfig(BoltConnector.enabled, false);
        settings.accept(builder);
        return builder.build();
    }

    /**
     * Starts a server whose Neo4j home is {@code home}, so that its data, logs and metrics directory are still there
     * once it is closed and an offline tool such as {@code neo4j-admin} can be run against that home. The harness
     * deletes only its own randomly named server folder on close; redirecting {@code server.directories.neo4j_home}
     * leaves nothing of the server in it.
     */
    public static Neo4j startInHome(Path home, Consumer<Neo4jBuilder> settings) {
        return start(builder -> {
            builder.withConfig(GraphDatabaseSettings.neo4j_home, home.toAbsolutePath());
            settings.accept(builder);
        });
    }
}
