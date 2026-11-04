/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.dozerdb.metrics.adapter.MetricsLog;
import org.dozerdb.metrics.registry.ComponentFailureHandler;

/**
 * The isolation boundary, which makes sure a metrics failure never stops the server: construction and runtime of every collector and exporter run
 * inside it. A component that fails is logged once at error level through the server log and stays disabled;
 * nothing propagates to Neo4j's extension loader, so the server keeps serving without that component.
 */
final class IsolationBoundary implements ComponentFailureHandler {

    private final MetricsLog log;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    IsolationBoundary(MetricsLog log) {
        this.log = log;
    }

    /** Runs one component's action and reports whether it completed; a failure disables the component. */
    boolean run(String component, Action action) {
        if (disabled.contains(component)) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (Exception | LinkageError e) {
            componentFailed(component, e);
            return false;
        }
    }

    @Override
    public void componentFailed(String component, Throwable cause) {
        if (disabled.add(component)) {
            log.error(
                    "Metrics component '" + component + "' failed and is disabled; the server continues without it",
                    cause);
        }
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }
}
