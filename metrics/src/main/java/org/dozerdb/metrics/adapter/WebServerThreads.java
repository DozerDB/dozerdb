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
 * The web server's (Jetty's) thread pool as the server thread collector reads it, at report time. A server whose
 * web server is disabled still has one: Neo4j's stand-in for the disabled server reports a pool of zero threads.
 */
public interface WebServerThreads {

    /** Threads in the pool, idle and busy; {@code -1} while the web server is not started. */
    int all();

    /** Idle threads in the pool; {@code -1} while the web server is not started. */
    int idle();
}
