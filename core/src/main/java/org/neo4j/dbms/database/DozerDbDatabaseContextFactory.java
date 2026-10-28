/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */

package org.neo4j.dbms.database;

import java.util.Optional;
import org.neo4j.configuration.DatabaseConfig;
import org.neo4j.cypher.internal.javacompat.DozerDbCypherEngineProvider;
import org.neo4j.dbms.identity.ServerIdentity;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.HostedOnMode;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.ModularDatabaseCreationContext;
import org.neo4j.graphdb.factory.module.id.IdContextFactory;
import org.neo4j.io.device.DeviceMapper;
import org.neo4j.kernel.api.Kernel;
import org.neo4j.kernel.database.*;
import org.neo4j.kernel.impl.api.ExternalIdReuseConditionProvider;
import org.neo4j.kernel.impl.api.LeaseService;
import org.neo4j.kernel.impl.api.TransactionalProcessFactory;
import org.neo4j.kernel.impl.api.TransactionsFactory;
import org.neo4j.kernel.impl.constraints.DozerDbConstraintSemantics;
import org.neo4j.kernel.impl.factory.AccessCapabilityFactory;
import org.neo4j.kernel.impl.index.DatabaseIndexStats;
import org.neo4j.kernel.impl.pagecache.CommunityVersionStorageFactory;
import org.neo4j.kernel.impl.pagecache.IOControllerService;
import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;

/**
 * Factory for creating {@link StandaloneDatabaseContext} instances with DozerDB-specific components.
 *
 * <p>This factory wires together the DozerDB constraint semantics, Cypher engine provider, and other
 * edition-specific components that differentiate DozerDB from standard Neo4j Community Edition.
 * Each call to {@link #create(NamedDatabaseId, Optional)} produces a fully configured database
 * context backed by a new {@link Database} kernel instance.</p>
 */
public class DozerDbDatabaseContextFactory
        extends AbstractDatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> {
    private final DatabaseTransactionStats.Factory transactionStatsFactory;
    private final DatabaseIndexStats.Factory indexStatsFactory;
    private final DeviceMapper deviceMapper;
    private final IOControllerService controllerService;
    private final TransactionalProcessFactory commitProcessFactory;
    private final DefaultDatabaseContextFactoryComponents components;
    private final ServerIdentity serverIdentity;

    /**
     * Constructs a new DozerDbDatabaseContextFactory with all required dependencies.
     *
     * @param globalModule            the global module providing shared infrastructure (logging, config, etc.)
     * @param serverIdentity          the server identity for this Neo4j instance
     * @param transactionStatsFactory factory for creating per-database transaction statistics
     * @param indexStatsFactory       factory for creating per-database index statistics
     * @param idContextFactory        factory for creating database ID contexts
     * @param deviceMapper            mapper for block device information
     * @param controllerService       IO controller service for page cache management
     * @param commitProcessFactory    factory for creating the transactional commit process
     * @param components              edition-specific components (e.g., read-only database checker)
     */
    public DozerDbDatabaseContextFactory(
            final GlobalModule globalModule,
            final ServerIdentity serverIdentity,
            final DatabaseTransactionStats.Factory transactionStatsFactory,
            final DatabaseIndexStats.Factory indexStatsFactory,
            final IdContextFactory idContextFactory,
            final DeviceMapper deviceMapper,
            final IOControllerService controllerService,
            final TransactionalProcessFactory commitProcessFactory,
            final DefaultDatabaseContextFactoryComponents components) {
        super(globalModule, idContextFactory);
        this.serverIdentity = serverIdentity;
        this.transactionStatsFactory = transactionStatsFactory;
        this.indexStatsFactory = indexStatsFactory;
        this.deviceMapper = deviceMapper;
        this.controllerService = controllerService;
        this.commitProcessFactory = commitProcessFactory;
        this.components = components;
    }

    /**
     * Creates a new {@link StandaloneDatabaseContext} for the given database.
     *
     * @param namedDatabaseId the identifier of the database to create
     * @param ignored         unused parameter (required by the abstract factory contract)
     * @return a fully initialized standalone database context
     */
    @Override
    public StandaloneDatabaseContext create(final NamedDatabaseId namedDatabaseId, final Optional<?> ignored) {
        return new Creator(namedDatabaseId).context();
    }

    /**
     * Inner class that encapsulates the creation of a {@link Database} and its wrapping
     * {@link StandaloneDatabaseContext}. The constructor performs all wiring so that
     * {@link #context()} returns a ready-to-use context.
     */
    private class Creator {
        private final Database kernelDatabase;
        private final StandaloneDatabaseContext context;

        /**
         * Constructs the database kernel and context for the given database identifier.
         *
         * @param namedDatabaseId the identifier of the database to create
         */
        private Creator(final NamedDatabaseId namedDatabaseId) {
            final var databaseConfig = new DatabaseConfig(globalModule.getGlobalConfig());
            final var contextFactory = createContextFactory(databaseConfig, namedDatabaseId);
            final var creationContext = new ModularDatabaseCreationContext(
                    HostedOnMode.SINGLE,
                    serverIdentity,
                    namedDatabaseId,
                    globalModule,
                    globalModule.getGlobalDependencies(),
                    contextFactory,
                    deviceMapper,
                    new CommunityVersionStorageFactory(),
                    databaseConfig,
                    globalModule.getGlobalMonitors(),
                    LeaseService.NO_LEASES,
                    () -> DatabaseCreationContext.selectStorageEngine(
                            globalModule.getFileSystem(),
                            globalModule.getNeo4jLayout(),
                            databaseConfig,
                            namedDatabaseId),
                    new DozerDbConstraintSemantics(),
                    new DozerDbCypherEngineProvider(),
                    transactionStatsFactory.create(),
                    indexStatsFactory.create(),
                    ModularDatabaseCreationContext.defaultFileWatcherFilter(),
                    AccessCapabilityFactory.configDependent(),
                    ExternalIdReuseConditionProvider.NONE,
                    idContextFactory.createIdContext(namedDatabaseId, contextFactory, databaseConfig, true),
                    commitProcessFactory,
                    createTokenHolderProvider(this::kernel),
                    new GlobalAvailabilityGuardController(globalModule.getGlobalAvailabilityGuard()),
                    components.readOnlyDatabases(),
                    controllerService,
                    new DatabaseTracers(globalModule.getTracers(), namedDatabaseId),
                    globalModule.getDefaultCommandCommitListeners(),
                    TransactionsFactory.DEFAULT);
            kernelDatabase = new Database(creationContext);
            context = new StandaloneDatabaseContext(kernelDatabase);
        }

        /**
         * Returns the fully initialized database context.
         *
         * @return the standalone database context wrapping the kernel database
         */
        private StandaloneDatabaseContext context() {
            return context;
        }

        /**
         * Resolves the {@link Kernel} from the database's dependency resolver.
         *
         * @return the kernel instance for this database
         */
        private Kernel kernel() {
            return kernelDatabase.getDependencyResolver().resolveDependency(Kernel.class);
        }
    }
}
