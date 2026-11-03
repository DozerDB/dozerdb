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

import java.util.Optional;
import java.util.function.Supplier;
import org.dozerdb.metrics.adapter.WebServerThreads;
import org.dozerdb.metrics.registry.MetricRegistration;

/**
 * Server metrics (Operations Manual 5, "Server metrics"): {@code server.threads.jetty.all} and
 * {@code server.threads.jetty.idle}, the threads of the web server's pool, global. The web server is resolved when
 * the collector registers; a server with its web server disabled reports a pool of zero threads (Neo4j's own
 * stand-in says so), and a server without any web server component registers neither gauge and logs nothing.
 */
public final class ServerThreadsCollector implements Collector {

    public static final String NAME = "server.threads.jetty";

    private final Supplier<Optional<WebServerThreads>> webServer;

    public ServerThreadsCollector(Supplier<Optional<WebServerThreads>> webServer) {
        this.webServer = webServer;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(MetricRegistration metrics) {
        webServer.get().ifPresent(threads -> {
            metrics.gauge("server.threads.jetty.all", threads::all);
            metrics.gauge("server.threads.jetty.idle", threads::idle);
        });
    }
}
