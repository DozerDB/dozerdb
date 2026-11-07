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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The four services the extension takes from the server, each behind a supplier: the upstream adapter's factory
 * builds this record from Neo4j's dependencies and hands it over, so the extension names no adapter class, and each
 * service is resolved only when {@link QueryLogExtension#init()} asks for it, inside the isolation boundary, because
 * Neo4j resolves an extension's dependencies on the call and a lookup that fails must not fail outside the boundary.
 *
 * @param serverLog the server's own log, where failures are reported
 * @param queryLog the {@code QueryLogger} handle the entries are written through
 * @param settings the {@code db.logs.query.*} values and their changes
 * @param listeners the registration of the two listeners on the monitor bus
 */
public record UpstreamServices(
        Supplier<ServerLog> serverLog,
        Supplier<QueryLogWriter> queryLog,
        Supplier<DynamicConfiguration> settings,
        Supplier<MonitorListeners> listeners) {

    public UpstreamServices {
        Objects.requireNonNull(serverLog, "serverLog");
        Objects.requireNonNull(queryLog, "queryLog");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(listeners, "listeners");
    }
}
