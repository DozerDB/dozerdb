/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.dbms.database;

import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_LABEL;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_NAME_PROPERTY;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_STATUS_PROPERTY;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.database.NormalizedDatabaseName;

/**
 * The multi-database system graph component for DozerDB.
 *
 * <p>Neo4j's {@link DefaultSystemGraphComponent} verifies the system graph on every start by marking
 * every database other than the configured default and the system database offline, because Community
 * Edition hosts a single database. DozerDB hosts many databases whose status is owned by the
 * {@code status} property their administration commands maintain, so this component only guarantees
 * that the configured default database exists and is online and leaves every other database untouched.</p>
 */
public class DozerDbSystemGraphComponent extends DefaultSystemGraphComponent {

    private final NormalizedDatabaseName defaultDatabaseName;
    private final Clock clock;

    public DozerDbSystemGraphComponent(Config config, Clock clock) {
        super(config, clock);
        this.defaultDatabaseName = new NormalizedDatabaseName(config.get(initial_default_database));
        this.clock = clock;
    }

    /**
     * Ensures the configured default database node exists and is online. Unlike the community component,
     * other databases keep the status recorded in the system graph.
     */
    @Override
    protected void verifySystemGraph(GraphDatabaseService system) {
        try (Transaction tx = system.beginTx()) {
            Node defaultDb = tx.findNode(DATABASE_LABEL, DATABASE_NAME_PROPERTY, defaultDatabaseName.name());
            if (defaultDb == null) {
                var now = ZonedDateTime.ofInstant(clock.instant(), clock.getZone());
                createDatabaseNode(
                        tx,
                        defaultDatabaseName.name(),
                        UUID.randomUUID(),
                        now,
                        cypherVersionFromConfig(config.get(GraphDatabaseSettings.default_language)));
            } else {
                String online = TopologyGraphDbmsModel.DatabaseStatus.ONLINE.statusName();
                if (!online.equals(defaultDb.getProperty(DATABASE_STATUS_PROPERTY, null))) {
                    defaultDb.setProperty(DATABASE_STATUS_PROPERTY, online);
                }
            }
            tx.commit();
        }
    }
}
