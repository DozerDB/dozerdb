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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
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
 * 1. <b>Startup Banner:</b> Upon creation of the Neo4j database, it logs the release it runs as one line,
 * {@code DozerDB <dozerdb-version> (Neo4j <neo4j-tag>)}, read from the version resource the build filters into the core
 * JAR, followed by the message indicating the enhancement by the DozerDB plugin.
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

    /**
     * The version resource beside this class. The build writes the core artifact's version into it, the distribution
     * version {@code <neo4j-tag>-<dozerdb-version>}; it is never edited by hand.
     */
    static final String VERSION_RESOURCE = "dozerdb-version.properties";

    @Override
    protected DatabaseManagementService createNeo(
            Config config, boolean daemonMode, GraphDatabaseDependencies dependencies) {
        try {
            super.getLog().info(banner(readDistributionVersion()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // A broken build must not stop the server; the warning names the problem and carries no version.
            super.getLog().warn("DozerDB version unknown: " + e.getMessage());
        }
        super.getLog().info(DOZERDB_MESSAGE);

        DatabaseManagementServiceFactory facadeFactory =
                new DatabaseManagementServiceFactory(ENTERPRISE, DozerDbEditionModule::new);
        return facadeFactory.build(config, daemonMode, dependencies);
    }

    /**
     * The startup banner of a distribution version: split at the first hyphen, the Neo4j tag before it verbatim and the
     * DozerDB version after it, e.g. {@code 2026.10.1-1.2.0} gives {@code DozerDB 1.2.0 (Neo4j 2026.10.1)}.
     *
     * @throws IllegalArgumentException when the value has no hyphen or an empty half
     */
    static String banner(String distributionVersion) {
        int hyphen = distributionVersion.indexOf('-');
        if (hyphen <= 0 || hyphen == distributionVersion.length() - 1) {
            throw new IllegalArgumentException(
                    "distribution version must be <neo4j-tag>-<dozerdb-version>: '" + distributionVersion + "'");
        }
        return "DozerDB " + distributionVersion.substring(hyphen + 1) + " (Neo4j "
                + distributionVersion.substring(0, hyphen) + ")";
    }

    /**
     * The distribution version the build wrote into {@link #VERSION_RESOURCE}.
     *
     * @throws IllegalStateException when the resource is missing, unreadable, lacks the value or was not filtered
     */
    static String readDistributionVersion() {
        Properties properties = new Properties();
        try (InputStream in = DozerDbBootstrapper.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("resource " + VERSION_RESOURCE + " is missing from the core JAR");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("resource " + VERSION_RESOURCE + " is unreadable: " + e.getMessage(), e);
        }
        String version = properties.getProperty("version", "").strip();
        if (version.isEmpty() || version.contains("${")) {
            throw new IllegalStateException(
                    "resource " + VERSION_RESOURCE + " has no built version: '" + version + "'");
        }
        return version;
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
