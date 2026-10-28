/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.server;

import static org.neo4j.kernel.impl.factory.DbmsInfo.ENTERPRISE;

import java.nio.file.Path;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.config.Configuration;
import org.neo4j.graphdb.facade.DatabaseManagementServiceFactory;
import org.neo4j.graphdb.facade.GraphDatabaseDependencies;
import org.neo4j.graphdb.factory.module.edition.DozerDbEditionModule;

/**
 * The DozerDbBootstrapper class is an extension of Neo4j's NeoBootstrapper and serves as the foundation for initializing and configuring the DozerDB-enhanced version of the Neo4j database.
 * <p>
 * Key Features:
 * 1. <b>Custom Message Logging:</b> Upon creation of the Neo4j database, it logs a custom message indicating the enhancement by the DozerDB plugin.
 * 2. <b>Database Creation:</b> Overrides the 'createNeo' method from NeoBootstrapper to instantiate a DatabaseManagementService using the DozerDbEditionModule. This extension allows the application to leverage custom functionalities provided by DozerDB.
 * 3. <b>License Agreement Check:</b> Overrides the 'checkLicenseAgreement' method and always returns true, meaning that any specific license agreement checks are bypassed.
 * <p>
 * The class is part of the initialization process for the DozerDB, providing hooks for customization and extension of the underlying Neo4j database.
 */
public class DozerDbBootstrapper extends NeoBootstrapper {

    private static final String DOZERDB_MESSAGE =
            "\n\n*****************************************************************************\n"
                    + " *********************** Enhanced By DozerDB Plugin ***********************\n"
                    + "*****************************************************************************\n\n";

    @Override
    protected DatabaseManagementService createNeo(
            Config config, boolean daemonMode, GraphDatabaseDependencies dependencies) {
        super.getLog().info(DOZERDB_MESSAGE);

        DatabaseManagementServiceFactory facadeFactory =
                new DatabaseManagementServiceFactory(ENTERPRISE, DozerDbEditionModule::new);
        return facadeFactory.build(config, daemonMode, dependencies);
    }

    /**
     * Overrides the 'checkLicenseAgreement' method and always returns true, meaning that any specific license agreement checks are bypassed.
     *
     * @param homeDir
     * @param config
     * @param daemonMode
     * @return
     */
    @Override
    protected boolean checkLicenseAgreement(Path homeDir, Configuration config, boolean daemonMode) {
        return true;
    }
}
