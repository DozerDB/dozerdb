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

import org.neo4j.logging.InternalLog;
import org.neo4j.logging.internal.LogService;

/** Adapts Neo4j's internal log (the {@code debug.log} an administrator reads) to {@link MetricsLog}. */
public final class InternalLogAdapter implements MetricsLog {

    private final InternalLog log;

    private InternalLogAdapter(InternalLog log) {
        this.log = log;
    }

    public static MetricsLog of(LogService logService, Class<?> owner) {
        return new InternalLogAdapter(logService.getInternalLog(owner));
    }

    @Override
    public void info(String message) {
        log.info(message);
    }

    @Override
    public void error(String message, Throwable cause) {
        log.error(message, cause);
    }
}
