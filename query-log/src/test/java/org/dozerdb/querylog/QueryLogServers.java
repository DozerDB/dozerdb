/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.harness.DozerDbBuilders;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;
import org.neo4j.harness.internal.AbstractInProcessNeo4jBuilder;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/**
 * Starts an in-process DozerDB for the query log tests. Nothing about logging is configured: with
 * {@code server.logs.config} unset and no file at its default path, Neo4j loads its classpath default
 * {@code server-logs.xml}, whose {@code QueryLog} appender writes {@code <logs>/query.log} with the shipped pattern.
 * The web server is off and Bolt is off unless asked for, so two servers can run in one JVM beside a Neo4j installed
 * on the machine.
 */
public final class QueryLogServers {

    private QueryLogServers() {}

    /** A server with no network connector, driven through its embedded API. */
    public static Neo4j start(Consumer<Neo4jBuilder> settings) {
        Neo4jBuilder builder =
                DozerDbBuilders.newInProcessBuilder().withDisabledServer().withConfig(BoltConnector.enabled, false);
        settings.accept(builder);
        return builder.build();
    }

    /** A server whose Bolt connector (and the routing connector Neo4j starts beside it) listen on free loopback ports. */
    public static Neo4j startWithBolt(Consumer<Neo4jBuilder> settings) {
        return start(builder -> {
            builder.withConfig(BoltConnector.enabled, true)
                    .withConfig(BoltConnector.listen_address, new SocketAddress("localhost", 0))
                    .withConfig(GraphDatabaseSettings.routing_listen_address, new SocketAddress("localhost", 0));
            settings.accept(builder);
        });
    }

    public static Neo4j startWithBolt() {
        return startWithBolt(builder -> {});
    }

    /**
     * A server whose HTTP connector listens on a free loopback port ({@code Neo4j.httpURI()} names it, the harness's
     * default) beside Bolt and the routing connector on free ports, for tests that drive the server through Neo4j's
     * Query API: that API answers a request over an in-process Bolt channel, so it needs the Bolt connector too.
     */
    public static Neo4j startWithWebServer() {
        return DozerDbBuilders.newInProcessBuilder()
                .withConfig(BoltConnector.enabled, true)
                .withConfig(BoltConnector.listen_address, new SocketAddress("localhost", 0))
                .withConfig(GraphDatabaseSettings.routing_listen_address, new SocketAddress("localhost", 0))
                .build();
    }

    /**
     * A Bolt server whose Neo4j home is {@code home}, so that its {@code logs/query.log} outlives the server: the
     * harness deletes only its own randomly named folder on close, and a second server started in the same home
     * appends to the same file.
     */
    public static Neo4j startWithBoltInHome(Path home) {
        return startWithBolt(builder -> builder.withConfig(GraphDatabaseSettings.neo4j_home, home.toAbsolutePath()));
    }

    /**
     * Adds extension factories beside the service-loaded ones (the module's own stays active). The in-process builder
     * offers this outside the public {@code Neo4jBuilder} interface, so the cast lives here and nowhere else.
     */
    public static void withExtensionFactories(Neo4jBuilder builder, ExtensionFactory<?>... factories) {
        ((AbstractInProcessNeo4jBuilder) builder).withExtensionFactories(List.of(factories));
    }

    /**
     * Changes a dynamic setting on a running server the way the {@code dbms.setConfigValue} procedure does, by setting
     * it on the kernel's live {@code Config}, whose change listeners then run. The procedure itself comes from DozerDB's
     * procedures module and is proven against the query log once ({@code SetConfigValueProcedureIT}); the other tests
     * take this direct path so that they need no Bolt connector and do not depend on that module.
     */
    public static <T> void setDynamic(Neo4j server, Setting<T> setting, T value) {
        GraphDatabaseAPI database =
                (GraphDatabaseAPI) server.databaseManagementService().database("neo4j");
        database.getDependencyResolver().resolveDependency(Config.class).setDynamicByUser(setting, value, "test");
    }
}
