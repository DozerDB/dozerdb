/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import org.dozerdb.querylog.extension.EntryRenderer;
import org.dozerdb.querylog.extension.QueryLogExtension;
import org.dozerdb.querylog.extension.RenderedEntry;
import org.dozerdb.querylog.extension.UpstreamServices;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

/**
 * Activates the query log as one GLOBAL Neo4j extension, discovered through Java service lookup: Neo4j's entry point
 * into the module and therefore part of the upstream adapter. GLOBAL because a listener on the global
 * monitor bus hears every database once plus the failures that never reach a database, and because Neo4j's offline
 * tools instantiate DATABASE extensions only.
 *
 * <p>Neo4j loads extensions with a fail strategy: anything thrown while an extension is constructed or initialised
 * stops the DBMS. {@link #newInstance} therefore only allocates; the set-up runs in the lifecycle's {@code init()}
 * inside the module's isolation boundary. {@code init()}, not {@code start()}, because the system database
 * starts between the two and its start-up transactions would otherwise go unheard.
 *
 * <p>This factory is the module's composition root: {@link #lifecycleOf(Neo4jDependencies, EntryRenderer)} wires the
 * four adapters into the extension's {@link UpstreamServices}, each behind a supplier so that it is resolved in
 * {@code init()}, and the extension names no adapter class. The method is public so that an in-server test can run a
 * second query log beside the real one with a renderer of its own (one that throws, one that counts).
 */
@ServiceProvider
public final class QueryLogExtensionFactory extends ExtensionFactory<Neo4jDependencies> {

    public static final String NAME = "dozerdb-query-log";

    public QueryLogExtensionFactory() {
        super(ExtensionType.GLOBAL, NAME);
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Neo4jDependencies dependencies) {
        return lifecycleOf(dependencies, RenderedEntry::of);
    }

    /**
     * The query log over {@code dependencies} rendering with {@code renderer}, adapted to Neo4j's lifecycle: the
     * extension's two phases become {@code init()} and {@code shutdown()}, {@code start()} and {@code stop()} stay
     * no-ops.
     */
    public static Lifecycle lifecycleOf(Neo4jDependencies dependencies, EntryRenderer renderer) {
        UpstreamServices services = new UpstreamServices(
                () -> ServerLogAdapter.of(dependencies),
                () -> QueryLogWriterAdapter.of(dependencies),
                () -> ConfigurationAdapter.of(dependencies),
                () -> MonitorListenersAdapter.of(dependencies));
        QueryLogExtension extension = new QueryLogExtension(services, renderer);
        return new LifecycleAdapter() {
            @Override
            public void init() {
                extension.init();
            }

            @Override
            public void shutdown() {
                extension.shutdown();
            }
        };
    }
}
