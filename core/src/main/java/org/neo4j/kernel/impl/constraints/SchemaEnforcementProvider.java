/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.neo4j.kernel.impl.constraints;

import java.util.function.Function;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

class SchemaEnforcementProvider {

    static final SchemaEnforcementProvider PASSTHROUGH = new SchemaEnforcementProvider(null, null);

    static final class Resolver implements Function<StorageReader, SchemaEnforcementProvider> {

        static final Resolver INSTANCE = new Resolver();

        private Resolver() {}

        @Override
        public SchemaEnforcementProvider apply(final StorageReader storageReader) {
            final var registryOpt = IntegrityRuleRegistry.compileFromSchema(storageReader.constraintsGetAll());
            if (registryOpt.isEmpty()) {
                return PASSTHROUGH;
            }
            final var faultEmitter =
                    new ValidationFaultEmitter(storageReader.constraintsGetAll(), storageReader.tokenNameLookup());
            return new SchemaEnforcementProvider(registryOpt.get(), faultEmitter);
        }
    }

    static final Resolver RESOLVER = Resolver.INSTANCE;

    private final IntegrityRuleRegistry ruleRegistry;

    private final ValidationFaultEmitter faultEmitter;

    private SchemaEnforcementProvider(
            final IntegrityRuleRegistry ruleRegistry, final ValidationFaultEmitter faultEmitter) {
        this.ruleRegistry = ruleRegistry;
        this.faultEmitter = faultEmitter;
    }

    static SchemaEnforcementProvider obtain(final StorageReader storageReader) {
        return storageReader.getOrCreateSchemaDependantState(SchemaEnforcementProvider.class, RESOLVER);
    }

    TxStateVisitor decorateVisitor(
            final CursorContext cursorContext,
            final CursorFactory cursorFactory,
            final MemoryTracker memoryTracker,
            final Read read,
            final TxStateVisitor txStateVisitor) {

        if (ruleRegistry == null) {
            return txStateVisitor;
        }

        return new GraphMutationPipeline(
                txStateVisitor,
                ruleRegistry,
                faultEmitter,
                read,
                cursorFactory.allocateFullAccessNodeCursor(cursorContext, memoryTracker),
                cursorFactory.allocateFullAccessPropertyCursor(cursorContext, memoryTracker),
                cursorFactory,
                cursorContext,
                memoryTracker);
    }
}
