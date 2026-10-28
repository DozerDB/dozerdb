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

import static org.neo4j.kernel.DatabaseCreationOptions.EMPTY_CREATION_OPTIONS;

import java.util.Map;
import org.neo4j.cypher.internal.javacompat.DozerDbCypherEngineProvider;
import org.neo4j.dbms.identity.ServerIdentity;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.HostedOnMode;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.ModularDatabaseCreationContext;
import org.neo4j.io.device.DeviceMapper;
import org.neo4j.kernel.api.Kernel;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseCreationContext;
import org.neo4j.kernel.database.DatabaseMonitors;
import org.neo4j.kernel.database.DatabaseMonitorsFactory;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.database.DefaultDatabaseMonitorsFactory;
import org.neo4j.kernel.database.GlobalAvailabilityGuardController;
import org.neo4j.kernel.database.IdContextFactory;
import org.neo4j.kernel.database.IdGeneratorSettings;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.api.ExternalIdReuseConditionProvider;
import org.neo4j.kernel.impl.api.LeaseService;
import org.neo4j.kernel.impl.api.TransactionalProcessFactory;
import org.neo4j.kernel.impl.api.TransactionsFactory;
import org.neo4j.kernel.impl.constraints.DozerDbConstraintSemantics;
import org.neo4j.kernel.impl.core.IsolatedTransactionVectorStoreCreator;
import org.neo4j.kernel.impl.factory.AccessCapabilityFactory;
import org.neo4j.kernel.impl.index.DatabaseIndexStats;
import org.neo4j.kernel.impl.pagecache.CommunityVersionStorageFactory;
import org.neo4j.kernel.impl.pagecache.IOControllerService;
import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;
import org.neo4j.logging.internal.DatabaseLogIdentifier;
import org.neo4j.logging.internal.DatabaseLogProvider;
import org.neo4j.wal.pruning.LogPruneStrategyFactory;

/**
 * Factory for creating {@link StandaloneDatabaseContext} instances with DozerDB-specific components.
 *
 * <p>Derived from Neo4j's default database context factory. The DozerDB delta is the
 * {@link DozerDbConstraintSemantics} and the {@link DozerDbCypherEngineProvider} wired into every
 * database kernel; everything else follows the upstream creation context.</p>
 */
public class DozerDbDatabaseContextFactory
        extends AbstractDatabaseContextFactory<StandaloneDatabaseContext, NamedDatabaseId> {
    private final DatabaseTransactionStats.Factory transactionStatsFactory;
    private final DatabaseIndexStats.Factory indexStatsFactory;
    private final DeviceMapper deviceMapper;
    private final IOControllerService controllerService;
    private final TransactionalProcessFactory commitProcessFactory;
    private final DefaultDatabaseContextFactoryComponents components;
    private final ServerIdentity serverIdentity;

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
     * @return a fully initialized standalone database context
     */
    @Override
    public StandaloneDatabaseContext create(final NamedDatabaseId namedDatabaseId) {
        return new Creator(namedDatabaseId).context();
    }

    /**
     * Encapsulates the creation of a {@link Database} and its wrapping {@link StandaloneDatabaseContext}.
     */
    private class Creator {
        private final Database kernelDatabase;
        private final StandaloneDatabaseContext context;

        private Creator(final NamedDatabaseId namedDatabaseId) {
            final var databaseConfig = createDatabaseConfig(namedDatabaseId, Map.of());
            final var contextFactory = createContextFactorySupplier(databaseConfig, namedDatabaseId);
            final var databaseLogIdentifier = DatabaseLogIdentifier.create(namedDatabaseId);
            final var creationContext = new ModularDatabaseCreationContext(
                    HostedOnMode.SINGLE,
                    serverIdentity,
                    namedDatabaseId,
                    databaseLogIdentifier,
                    globalModule,
                    globalModule.getGlobalDependencies(),
                    contextFactory,
                    deviceMapper,
                    new CommunityVersionStorageFactory(),
                    databaseConfig,
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
                    idContextFactory,
                    new IdGeneratorSettings(true, true),
                    commitProcessFactory,
                    createTokenHolderProvider(this::kernel),
                    new IsolatedTransactionVectorStoreCreator(this::kernel),
                    new GlobalAvailabilityGuardController(globalModule.getGlobalAvailabilityGuard()),
                    components.readOnlyDatabases(),
                    controllerService,
                    new DatabaseTracers(globalModule.getTracers(), namedDatabaseId),
                    globalModule.getDefaultCommandCommitListeners(),
                    TransactionsFactory.DEFAULT,
                    databaseMonitorsFactory(databaseLogIdentifier),
                    globalModule.getExceptionHandlerService(),
                    EMPTY_CREATION_OPTIONS,
                    new LogPruneStrategyFactory(),
                    false,
                    false);
            kernelDatabase = new Database(creationContext);
            context = new StandaloneDatabaseContext(kernelDatabase);
        }

        private DatabaseMonitorsFactory databaseMonitorsFactory(DatabaseLogIdentifier databaseLogIdentifier) {
            return new DefaultDatabaseMonitorsFactory(new DatabaseMonitors(
                    globalModule.getGlobalMonitors(),
                    new DatabaseLogProvider(
                            databaseLogIdentifier, globalModule.getLogService().getInternalLogProvider())));
        }

        private StandaloneDatabaseContext context() {
            return context;
        }

        private Kernel kernel() {
            return kernelDatabase.getDependencyResolver().resolveDependency(Kernel.class);
        }
    }
}
