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

/**
 * The query log's two listeners on Neo4j's global monitor bus, one for query events and one for transaction events
 * (one listener hears every database once), as the upstream adapter registers and removes them. Registered in
 * {@code init()}, removed in {@code shutdown()}, so the system database's start-up transactions are heard too.
 */
public interface MonitorListeners {

    /** Adds both listeners; query events are forwarded to {@code queries}, transaction events to {@code transactions}. */
    void register(QueryEvents queries, TransactionEvents transactions);

    /** Removes both listeners; the bus keeps nothing of the query log afterwards. */
    void unregister();
}
