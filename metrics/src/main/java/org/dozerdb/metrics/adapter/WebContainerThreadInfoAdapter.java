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

import java.util.Optional;
import org.neo4j.exceptions.UnsatisfiedDependencyException;
import org.neo4j.server.web.WebContainerThreadInfo;

/**
 * Adapts Neo4j's {@link WebContainerThreadInfo} to {@link WebServerThreads}. This is the most fragile hook of the
 * module: the interface is implemented by {@code JettyWebServer}, which
 * {@code CommunityNeoWebServer.createWebServer()} satisfies in the global dependencies when the web server is
 * initialised, and by {@code DisabledNeoWebServer}, which {@code DatabaseManagementServiceFactory} satisfies there
 * when the HTTP and HTTPS connectors are off. Neo4j initialises every component before it starts any, so by the time
 * this extension starts one of the two is resolvable by its interface, and no cast through
 * {@code AbstractNeoWebServer.getWebServer()} is needed. A Neo4j that satisfies neither leaves {@link #of} empty and
 * the collector registers nothing, without an error.
 */
public final class WebContainerThreadInfoAdapter implements WebServerThreads {

    private final WebContainerThreadInfo threads;

    private WebContainerThreadInfoAdapter(WebContainerThreadInfo threads) {
        this.threads = threads;
    }

    /** The web server's thread pool, when the server has a web server or its disabled stand-in. */
    public static Optional<WebServerThreads> of(Neo4jDependencies dependencies) {
        try {
            return Optional.of(new WebContainerThreadInfoAdapter(dependencies.webContainerThreadInfo()));
        } catch (UnsatisfiedDependencyException e) {
            return Optional.empty();
        }
    }

    @Override
    public int all() {
        return threads.allThreads();
    }

    @Override
    public int idle() {
        return threads.idleThreads();
    }
}
