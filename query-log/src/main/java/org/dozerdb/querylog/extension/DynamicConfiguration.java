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

import java.util.function.Consumer;

/**
 * The eleven {@code db.logs.query.*} settings as Neo4j holds them, read whole and followed as they change. The upstream
 * adapter implements it over Neo4j's configuration, whose declarations of these settings the module reuses instead of
 * declaring any of its own, and whose change listeners it subscribes to, one per setting, so that a runtime change
 * reaches the query log without a restart. Registered in {@code init()}, removed in {@code shutdown()}.
 */
public interface DynamicConfiguration {

    /** The values in force now. */
    QueryLogConfiguration read();

    /** Subscribes to every one of the settings; each change hands {@code onChange} the whole record rebuilt. */
    void register(Consumer<QueryLogConfiguration> onChange);

    /** Removes every subscription; Neo4j's configuration keeps nothing of the query log afterwards. */
    void unregister();
}
