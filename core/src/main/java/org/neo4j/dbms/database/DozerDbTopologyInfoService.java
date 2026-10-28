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

import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_LABEL;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_STATUS_PROPERTY;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_UUID_PROPERTY;

import java.util.Set;
import java.util.stream.Collectors;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.database.NamedDatabaseId;

/**
 * Topology information for {@code SHOW DATABASES} and friends.
 *
 * <p>Neo4j's {@link DefaultTopologyInfoService} reports every database's requested status as
 * {@code online}, because Community Edition never stops its single database. DozerDB records the
 * requested status of each database in the system graph when {@code START DATABASE} and
 * {@code STOP DATABASE} run, so this service delegates to the default one and replaces the requested
 * status with the value stored on the database node.</p>
 */
public class DozerDbTopologyInfoService implements TopologyInfoService {

    private final TopologyInfoService delegate;

    public DozerDbTopologyInfoService(final TopologyInfoService delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<ServerDetails> servers(final Transaction transaction) {
        return delegate.servers(transaction);
    }

    @Override
    public Set<DatabaseDetails> databases(
            final Transaction transaction, final Set<NamedDatabaseId> databaseIds, final RequestedExtras extras) {
        return delegate.databases(transaction, databaseIds, extras).stream()
                .map(details -> withRecordedRequestedStatus(details, transaction))
                .collect(Collectors.toSet());
    }

    /**
     * Replaces the requested status with the {@code status} property of the database node in the
     * system graph when it is available and differs; the default service already derives the
     * current status and timestamps from the same node.
     */
    private static DatabaseDetails withRecordedRequestedStatus(
            final DatabaseDetails details, final Transaction transaction) {
        if (transaction == null || details.namedDatabaseId().isSystemDatabase()) {
            return details;
        }
        final Node node = transaction.findNode(
                DATABASE_LABEL,
                DATABASE_UUID_PROPERTY,
                details.namedDatabaseId().databaseId().uuid().toString());
        if (node == null) {
            return details;
        }
        final Object recorded = node.getProperty(DATABASE_STATUS_PROPERTY, null);
        if (!(recorded instanceof String requestedStatus) || requestedStatus.equals(details.requestedStatus())) {
            return details;
        }
        return new DatabaseDetails(
                details.serverId(),
                details.databaseAccess(),
                details.boltAddress(),
                details.role(),
                details.writer(),
                details.actualStatus(),
                details.statusMessage(),
                details.lastCommittedTxId(),
                details.txCommitLag(),
                details.shardCommitLag(),
                details.namedDatabaseId(),
                requestedStatus,
                details.type(),
                details.options(),
                details.storeId(),
                details.externalStoreId(),
                details.requestedPrimariesCount(),
                details.actualPrimariesCount(),
                details.requestedSecondariesCount(),
                details.actualSecondariesCount(),
                details.creationTime(),
                details.lastStartTime(),
                details.lastStopTime(),
                details.cypherVersion());
    }
}
