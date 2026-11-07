/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ServiceLoader;
import org.dozerdb.querylog.adapter.QueryLogExtensionFactory;
import org.junit.jupiter.api.Test;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;

/**
 * Neo4j discovers extension factories through Java service lookup. The query log module activates itself that way,
 * with no change to core, so its one factory must be visible from the built classes, and it must be a
 * GLOBAL extension: Neo4j's offline tools ({@code neo4j-admin}) instantiate DATABASE extensions for the store they
 * open and never GLOBAL ones, so this is what keeps the query log out of them.
 */
class ServiceDiscoveryTest {

    @Test
    void theExtensionFactoryIsFoundThroughServiceLookup() {
        assertThat(ServiceLoader.load(ExtensionFactory.class).stream().map(ServiceLoader.Provider::type))
                .contains(QueryLogExtensionFactory.class);
    }

    @Test
    void theExtensionIsGlobalSoOfflineToolsDoNotLoadIt() throws ReflectiveOperationException {
        ExtensionFactory<?> factory = ServiceLoader.load(ExtensionFactory.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(QueryLogExtensionFactory.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertThat(extensionTypeOf(factory)).isEqualTo(ExtensionType.GLOBAL);
    }

    /** The type is a package-private accessor of Neo4j's factory base class; this is the only way to read it. */
    private static ExtensionType extensionTypeOf(ExtensionFactory<?> factory) throws ReflectiveOperationException {
        Method accessor = ExtensionFactory.class.getDeclaredMethod("getExtensionType");
        accessor.setAccessible(true);
        return (ExtensionType) accessor.invoke(factory);
    }
}
