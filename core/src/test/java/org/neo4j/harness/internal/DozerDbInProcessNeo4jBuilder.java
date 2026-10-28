/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.harness.internal;

import static org.neo4j.kernel.impl.factory.DbmsInfo.ENTERPRISE;

import java.nio.file.Path;
import org.apache.commons.lang3.SystemUtils;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.facade.DatabaseManagementServiceFactory;
import org.neo4j.graphdb.facade.ExternalDependencies;
import org.neo4j.graphdb.factory.module.edition.DozerDbEditionModule;

/**
 * An in-process Neo4j builder that bootstraps the DozerDb edition module,
 * enabling enterprise features (constraints, multi-database, etc.) in test
 * harness mode without requiring a running server.
 *
 * <p>Mirrors {@code InProcessNeo4jBuilder} but swaps {@code CommunityEditionModule}
 * for {@code DozerDbEditionModule} and uses {@code DbmsInfo.ENTERPRISE}.</p>
 */
public class DozerDbInProcessNeo4jBuilder extends AbstractInProcessNeo4jBuilder {

    /**
     * Creates a builder using the standard Java temp directory as the working directory.
     */
    public DozerDbInProcessNeo4jBuilder() {
        this(SystemUtils.getJavaIoTmpDir().toPath());
    }

    /**
     * Creates a builder using the specified directory as the working directory.
     *
     * @param workingDir the directory where temporary Neo4j data will be stored
     */
    public DozerDbInProcessNeo4jBuilder(final Path workingDir) {
        withWorkingDir(workingDir);
    }

    /**
     * Creates the {@link DatabaseManagementService} using the DozerDb enterprise edition module.
     *
     * @param config       the Neo4j configuration
     * @param dependencies external dependencies for the database
     * @return a fully initialized {@link DatabaseManagementService} with enterprise capabilities
     */
    @Override
    protected DatabaseManagementService createNeo(final Config config, final ExternalDependencies dependencies) {
        final DatabaseManagementServiceFactory facadeFactory =
                new DatabaseManagementServiceFactory(ENTERPRISE, DozerDbEditionModule::new);
        return facadeFactory.build(config, false, dependencies);
    }
}
