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

import org.neo4j.configuration.Config;
import org.neo4j.dbms.DatabaseStateService;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseContextProvider;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.logging.internal.LogService;
import org.neo4j.memory.MemoryPools;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.server.web.WebContainerThreadInfo;

/**
 * The Neo4j services the metrics extension needs, declared as the dependencies interface of the extension factory.
 *
 * <p>Neo4j implements this interface with a proxy that resolves each method from the global dependency resolver
 * at call time, so nothing is resolved while the extension is constructed: every service is reachable from
 * {@code start()} even when it was registered after the extension was built.
 */
public interface Neo4jDependencies {

    /** The server configuration, including every {@code server.metrics.*} setting. */
    Config config();

    /** The server's log service; the metrics module logs through its internal log. */
    LogService logService();

    /** The server's job scheduler; exporters report on its metrics groups, never on a database thread. */
    JobScheduler jobScheduler();

    /** The database management service (public API); database event listeners are registered through it. */
    DatabaseManagementService databaseManagementService();

    /** The server's databases with their own dependency resolvers, from which database collectors are built. */
    DatabaseContextProvider<?> databaseContextProvider();

    /** The server's memory pools, global and per database; the pool collectors read Neo4j's accounting from them. */
    MemoryPools memoryPools();

    /** The global monitor bus; event-driven collectors listen to it (the VM pause monitor and the Bolt server's connection and driver telemetry monitors among them). */
    Monitors monitors();

    /**
     * The web server's thread pool: Jetty's when the HTTP or HTTPS connector is enabled, Neo4j's zero-thread stand-in
     * when both are disabled. Satisfied during the web server's initialisation, before this extension starts.
     */
    WebContainerThreadInfo webContainerThreadInfo();

    /** The operator state and failure of every registered database, as {@code SHOW DATABASES} reports them. */
    DatabaseStateService<?> databaseStateService();

    /** The global page cache tracer Neo4j builds at start and counts page cache events with; read, never replaced. */
    PageCacheTracer pageCacheTracer();

    /** The server's one page cache, for its page counts. */
    PageCache pageCache();
}
