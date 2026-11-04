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

import org.dozerdb.metrics.extension.MetricsExtension;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

/**
 * Activates the metrics module as one global Neo4j extension, discovered through Java service lookup: Neo4j's entry
 * point into the module, and therefore part of the upstream adapter, because the extension factory and lifecycle
 * types are Neo4j internals. It hands the module's {@link MetricsExtension} Neo4j's lazy dependencies and adapts its
 * start and stop to Neo4j's lifecycle.
 *
 * <p>Neo4j loads extensions with a fail strategy: anything thrown while an extension is constructed or
 * initialised stops the DBMS. The constructor and {@link #newInstance} therefore do nothing but allocate; all
 * work happens in the lifecycle's {@code start()}, inside the isolation boundary.
 */
@ServiceProvider
public final class MetricsExtensionFactory extends ExtensionFactory<Neo4jDependencies> {

    public static final String NAME = "dozerdb-metrics";

    public MetricsExtensionFactory() {
        super(ExtensionType.GLOBAL, NAME);
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Neo4jDependencies dependencies) {
        MetricsExtension extension = new MetricsExtension(dependencies);
        return new LifecycleAdapter() {
            @Override
            public void start() {
                extension.start();
            }

            @Override
            public void stop() {
                extension.stop();
            }
        };
    }
}
