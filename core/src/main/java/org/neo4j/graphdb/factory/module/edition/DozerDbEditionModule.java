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

package org.neo4j.graphdb.factory.module.edition;

import static org.neo4j.configuration.GraphDatabaseSettings.SYSTEM_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.dbms.database.DatabaseContextProviderDelegate.delegate;
import static org.neo4j.dbms.routing.RoutingTableTTLProvider.ttlFromConfig;

import java.util.Set;
import org.neo4j.bolt.dbapi.BoltGraphDatabaseManagementServiceSPI;
import org.neo4j.bolt.tx.TransactionManager;
import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.CommunityDatabaseStateService;
import org.neo4j.dbms.CommunityKernelPanicListener;
import org.neo4j.dbms.DatabaseStateService;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.database.*;
import org.neo4j.dbms.database.readonly.ReadOnlyChangeListener;
import org.neo4j.dbms.database.readonly.ReadOnlyDatabases;
import org.neo4j.dbms.database.readonly.SystemGraphReadOnlyDatabaseLookupFactory;
import org.neo4j.dbms.database.readonly.SystemGraphReadOnlyListener;
import org.neo4j.dbms.identity.DefaultIdentityModule;
import org.neo4j.dbms.identity.ServerIdentity;
import org.neo4j.dbms.identity.ServerIdentityFactory;
import org.neo4j.dbms.routing.*;
import org.neo4j.dbms.systemgraph.CommunityTopologyGraphComponent;
import org.neo4j.dbms.systemgraph.SystemDatabaseProvider;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.internal.kernel.api.security.CommunitySecurityLog;
import org.neo4j.io.device.DeviceMapper;
import org.neo4j.kernel.api.security.SecurityModule;
import org.neo4j.kernel.api.security.provider.NoAuthSecurityProvider;
import org.neo4j.kernel.api.security.provider.SecurityProvider;
import org.neo4j.kernel.database.*;
import org.neo4j.kernel.impl.api.TransactionalProcessFactory;
import org.neo4j.kernel.impl.factory.DbmsInfo;
import org.neo4j.kernel.impl.factory.DefaultTransactionalProcessFactory;
import org.neo4j.kernel.impl.pagecache.CommunityIOControllerService;
import org.neo4j.kernel.impl.security.URIAccessRules;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.ProcedureConfig;
import org.neo4j.router.CommunityQueryRouterBootstrap;
import org.neo4j.server.CommunityNeoWebServer;
import org.neo4j.server.config.AuthConfigProvider;
import org.neo4j.server.rest.repr.CommunityAuthConfigProvider;
import org.neo4j.server.security.auth.CommunitySecurityModule;
import org.neo4j.server.security.systemgraph.CommunityDefaultDatabaseResolver;
import org.neo4j.ssl.config.SslPolicyLoader;

/**
 * This class extends the CommunityEditionModule code adding in the appropriate hooks to enable DozerDb additional features such as multi database.
 */
public class DozerDbEditionModule extends AbstractEditionModule implements DefaultDatabaseContextFactoryComponents {
    protected final SslPolicyLoader sslPolicyLoader;
    protected final GlobalModule globalModule;
    protected final ServerIdentity identityModule;
    private final MapCachingDatabaseReferenceRepository databaseReferenceRepo;
    private final DeviceMapper deviceMapper;
    private final InternalLogProvider logProvider;
    private final CommunitySecurityLog securityLog;
    protected DatabaseStateService databaseStateService;
    private Lifecycle defaultDatabaseInitializer = new LifecycleAdapter();
    private SystemGraphComponents systemGraphComponents;

    /**
     * Constructs the DozerDB edition module, initializing SSL, identity, device mapping,
     * connection tracking, and security logging infrastructure.
     *
     * @param globalModule the global module providing shared infrastructure
     */
    public DozerDbEditionModule(GlobalModule globalModule) {
        Dependencies globalDependencies = globalModule.getGlobalDependencies();
        Config globalConfig = globalModule.getGlobalConfig();
        LogService logService = globalModule.getLogService();
        this.globalModule = globalModule;

        this.sslPolicyLoader =
                SslPolicyLoader.create(globalModule.getFileSystem(), globalConfig, logService.getInternalLogProvider());
        globalDependencies.satisfyDependency(sslPolicyLoader); // for bolt and web server
        globalDependencies.satisfyDependency(new DatabaseOperationCounts.Counter()); // for global metrics
        globalDependencies.satisfyDependency(new DatabaseStateMonitor.Counter()); // for global metrics

        globalDependencies.satisfyDependency(createAuthConfigProvider(globalModule));

        logProvider = globalModule.getLogService().getInternalLogProvider();
        securityLog = new CommunitySecurityLog(logProvider.getLog(CommunitySecurityModule.class));
        globalDependencies.satisfyDependency(new URIAccessRules(securityLog, globalConfig));

        identityModule = tryResolveOrCreate(
                        ServerIdentityFactory.class,
                        globalModule.getExternalDependencyResolver(),
                        DefaultIdentityModule::fromGlobalModule)
                .create(globalModule);
        globalDependencies.satisfyDependency(identityModule);

        deviceMapper = DeviceMapper.UNKNOWN_MAPPER;
        globalDependencies.satisfyDependency(deviceMapper);

        connectionTracker = globalDependencies.satisfyDependency(createConnectionTracker());
        databaseReferenceRepo = globalDependencies.satisfyDependency(new MapCachingDatabaseReferenceRepository());
    }

    /**
     * Creates and wires the database context provider, including the multi-database manager,
     * lifecycle service, panic listener, and system graph transaction listeners.
     *
     * @param globalModule the global module providing shared infrastructure
     * @return the configured database repository acting as the database context provider
     */
    @Override
    public DatabaseContextProvider<?> createDatabaseContextProvider(GlobalModule globalModule) {
        var databaseContextFactory = new DozerDbDatabaseContextFactory(
                globalModule,
                identityModule,
                getTransactionMonitorFactory(),
                getIndexMonitorFactory(),
                createIdContextFactory(globalModule),
                deviceMapper,
                new CommunityIOControllerService(),
                createCommitProcessFactory(),
                this);

        var databaseIdRepo = new MapCachingDatabaseIdRepository();
        var databaseRepository = new DatabaseRepository<StandaloneDatabaseContext>(databaseIdRepo);
        var rootDatabaseIdRepository = DozerDbEditionModule.tryResolveOrCreate(
                DatabaseIdRepository.class,
                globalModule.getExternalDependencyResolver(),
                () -> new SystemGraphDatabaseIdRepository(
                        () -> databaseRepository.getDatabaseContext(DatabaseId.SYSTEM_DATABASE_ID),
                        globalModule.getLogService().getInternalLogProvider()));
        var rootDatabaseReferenceRepository = DozerDbEditionModule.tryResolveOrCreate(
                DatabaseReferenceRepository.class,
                globalModule.getExternalDependencyResolver(),
                () -> new SystemGraphDatabaseReferenceRepository(databaseRepository::getSystemDatabaseContext));
        databaseIdRepo.setDelegate(rootDatabaseIdRepository);
        databaseReferenceRepo.setDelegate(rootDatabaseReferenceRepository);
        var databaseIdCacheCleaner = new DatabaseReferenceCacheClearingListener(databaseIdRepo, databaseReferenceRepo);

        var kernelPanicListener =
                new CommunityKernelPanicListener(globalModule.getDatabaseEventListeners(), databaseRepository);
        globalModule.getGlobalLife().add(kernelPanicListener);

        final MultiDatabaseManager multiDatabaseManager =
                new MultiDatabaseManager(globalModule, databaseRepository, databaseContextFactory);
        setupMultiDbSystemGraphAdapter(multiDatabaseManager);

        final var databaseLifecycles = new MultiDatabaseLifecycleService(
                multiDatabaseManager,
                databaseRepository,
                globalModule.getGlobalConfig().get(initial_default_database),
                globalModule.getLogService().getInternalLogProvider());
        databaseStateService = new CommunityDatabaseStateService(databaseRepository);

        globalModule.getGlobalLife().add(databaseLifecycles.systemDatabaseStarter());
        globalModule.getGlobalLife().add(databaseLifecycles.allDatabaseStarter());
        globalModule.getGlobalLife().add(databaseLifecycles.allDatabaseShutdown());
        globalModule.getGlobalDependencies().satisfyDependency(delegate(databaseRepository));
        globalModule.getGlobalDependencies().satisfyDependency(databaseStateService);

        globalModule
                .getTransactionEventListeners()
                .registerTransactionEventListener(SYSTEM_DATABASE_NAME, databaseIdCacheCleaner);

        defaultDatabaseInitializer = databaseLifecycles.defaultDatabaseStarter();

        globalModule
                .getGlobalDependencies()
                .satisfyDependency(SystemGraphComponents.UpgradeChecker.UPGRADE_ALWAYS_ALLOWED);

        return databaseRepository;
    }

    /**
     * This method 'setupMultiDbSystemGraphAdapter' is used to set up a transaction event listener for the system database in a multi-database context.
     * <p>
     * It registers an instance of 'SystemGraphTransactionEventListenerAdapter' as a transaction event listener
     * for the named system database. The purpose of this setup is to handle and manage events triggered by
     * transactions on the system database, enabling it to respond to changes made during these transactions.
     * <p>
     * The method accepts a DatabaseManager object as a parameter which is then used to create a new instance
     * of 'SystemGraphTransactionEventListenerAdapter'. This newly created instance is registered as the transaction
     * event listener for the system database.
     *
     * @param multiDatabaseManager - The DatabaseManager instance to be used for managing databases.
     *                             This is used in the creation of the 'SystemGraphTransactionEventListenerAdapter' instance.
     */
    protected void setupMultiDbSystemGraphAdapter(MultiDatabaseManager multiDatabaseManager) {

        globalModule
                .getTransactionEventListeners()
                .registerTransactionEventListener(
                        SYSTEM_DATABASE_NAME,
                        new SystemGraphTransactionEventListenerAdapter(multiDatabaseManager, globalModule));
    }

    /**
     * Creates the global read-only database checker using system graph lookups and registers
     * the corresponding listener and dependency.
     *
     * @param systemDatabaseProvider  provider for the system database
     * @param databaseIdRepository    repository for resolving database IDs
     * @param globalModule            the global module providing shared infrastructure
     */
    @Override
    public void createGlobalReadOnlyChecker(
            SystemDatabaseProvider systemDatabaseProvider,
            DatabaseIdRepository databaseIdRepository,
            GlobalModule globalModule) {
        globalReadOnlyChecker = createGlobalReadOnlyChecker(
                Set.of(SystemGraphReadOnlyDatabaseLookupFactory.DEFAULT_PROVIDER),
                systemDatabaseProvider,
                databaseIdRepository,
                ReadOnlyChangeListener.NO_OP,
                globalModule);
        globalModule
                .getGlobalLife()
                .add(new SystemGraphReadOnlyListener(
                        globalModule.getTransactionEventListeners(), globalReadOnlyChecker));
        globalModule.getGlobalDependencies().satisfyDependency(globalReadOnlyChecker);
    }

    /** {@inheritDoc} */
    @Override
    public Lifecycle createWebServer(
            DatabaseManagementService managementService,
            TransactionManager transactionManager,
            Dependencies globalDependencies,
            Config config,
            InternalLogProvider userLogProvider,
            DbmsInfo dbmsInfo) {
        return new CommunityNeoWebServer(
                managementService,
                transactionManager,
                globalDependencies,
                config,
                userLogProvider,
                dbmsInfo,
                globalModule.getMemoryPools(),
                globalModule.getGlobalMonitors(),
                globalModule.getGlobalClock());
    }

    /** {@inheritDoc} */
    @Override
    public TopologyInfoService createTopologyInfoService(DatabaseContextProvider<?> databaseContextProvider) {
        var detailsExtrasProvider = new DefaultDatabaseDetailsExtrasProvider(databaseContextProvider);
        return new DefaultTopologyInfoService(
                identityModule.serverId(),
                globalModule.getGlobalConfig(),
                databaseStateService,
                globalReadOnlyChecker,
                detailsExtrasProvider);
    }

    /** {@inheritDoc} */
    @Override
    public RoutingService createRoutingService(
            DatabaseContextProvider<?> databaseContextProvider, ClientRoutingDomainChecker clientRoutingDomainChecker) {
        var logService = globalModule.getLogService();
        var portRegister = globalModule.getConnectorPortRegister();
        var config = globalModule.getGlobalConfig();
        var logProvider = globalModule.getLogService().getInternalLogProvider();
        var databaseAvailabilityChecker = new DefaultDatabaseAvailabilityChecker(databaseContextProvider);

        LocalRoutingTableServiceValidator validator =
                new LocalRoutingTableServiceValidator(databaseAvailabilityChecker);
        SingleAddressRoutingTableProvider routingTableProvider = new SingleAddressRoutingTableProvider(
                portRegister, RoutingOption.ROUTE_WRITE_AND_READ, config, logProvider, ttlFromConfig(config));

        return new DefaultRoutingService(
                logService.getInternalLogProvider(),
                validator,
                routingTableProvider,
                routingTableProvider,
                clientRoutingDomainChecker,
                config,
                () -> true,
                defaultDatabaseResolver,
                databaseReferenceRepo,
                true,
                globalModule.getGlobalClock());
    }

    /** {@inheritDoc} */
    @Override
    public ProcedureConfig getProcedureConfig(Config config) {
        return new ProcedureConfig(config, false);
    }

    /** {@inheritDoc} */
    @Override
    protected AuthConfigProvider createAuthConfigProvider(GlobalModule globalModule) {
        return new CommunityAuthConfigProvider();
    }

    /** {@inheritDoc} */
    @Override
    public void registerDatabaseInitializers(GlobalModule globalModule, SystemDatabaseProvider systemDatabaseProvider) {
        registerSystemGraphInitializer(globalModule, systemDatabaseProvider);
        registerDefaultDatabaseInitializer(globalModule);
    }

    /**
     * Registers the system graph initializer in the global lifecycle.
     *
     * @param globalModule            the global module providing shared infrastructure
     * @param systemDatabaseProvider  provider for the system database
     */
    private void registerSystemGraphInitializer(
            GlobalModule globalModule, SystemDatabaseProvider systemDatabaseProvider) {
        var initializer = AbstractEditionModule.tryResolveOrCreate(
                SystemGraphInitializer.class,
                globalModule.getExternalDependencyResolver(),
                () -> new DefaultSystemGraphInitializer(systemDatabaseProvider::database, systemGraphComponents));
        globalModule.getGlobalDependencies().satisfyDependency(initializer);
        globalModule.getGlobalLife().add(initializer);
    }

    /**
     * Registers the default database initializer in the global lifecycle.
     *
     * @param globalModule the global module providing shared infrastructure
     */
    protected void registerDefaultDatabaseInitializer(GlobalModule globalModule) {
        globalModule.getGlobalLife().add(defaultDatabaseInitializer);
    }

    /** {@inheritDoc} */
    @Override
    public void registerSystemGraphComponents(
            SystemGraphComponents.Builder systemGraphComponentsBuilder, GlobalModule globalModule) {
        var config = globalModule.getGlobalConfig();
        var log = globalModule.getLogService().getInternalLogProvider();
        var clock = globalModule.getGlobalClock();
        var systemGraphComponent = new DefaultSystemGraphComponent(config, clock);
        var communityTopologyGraphComponentComponent = new CommunityTopologyGraphComponent(config, log);
        systemGraphComponentsBuilder.register(systemGraphComponent);
        systemGraphComponentsBuilder.register(communityTopologyGraphComponentComponent);
        registerSecurityGraphComponent(systemGraphComponentsBuilder, globalModule);
        this.systemGraphComponents = systemGraphComponentsBuilder.build();
    }

    /**
     * Registers the community security graph component in the system graph.
     *
     * @param systemGraphComponentsBuilder the builder for system graph components
     * @param globalModule                 the global module providing shared infrastructure
     */
    private void registerSecurityGraphComponent(
            SystemGraphComponents.Builder systemGraphComponentsBuilder, GlobalModule globalModule) {
        var config = globalModule.getGlobalConfig();
        var fileSystem = globalModule.getFileSystem();

        var communityComponent = CommunitySecurityModule.createSecurityComponent(
                config,
                fileSystem,
                logProvider,
                securityLog,
                globalModule.getOtherMemoryPool().getPoolMemoryTracker());

        systemGraphComponentsBuilder.register(communityComponent);
    }

    /** {@inheritDoc} */
    @Override
    public void createSecurityModule(GlobalModule globalModule) {
        setSecurityProvider(makeSecurityModule(globalModule));
    }

    /** {@inheritDoc} */
    @Override
    public DatabaseReferenceRepository getDatabaseReferenceRepo() {
        return databaseReferenceRepo;
    }

    /**
     * Creates the security provider, either a full community security module (when auth is enabled)
     * or the no-auth provider.
     *
     * @param globalModule the global module providing shared infrastructure
     * @return the configured security provider
     */
    private SecurityProvider makeSecurityModule(GlobalModule globalModule) {
        globalModule.getGlobalDependencies().satisfyDependency(CommunitySecurityLog.NULL_LOG);
        if (globalModule.getGlobalConfig().get(GraphDatabaseSettings.auth_enabled)) {
            SecurityModule securityModule = new CommunitySecurityModule(
                    globalModule.getLogService(),
                    globalModule.getGlobalConfig(),
                    globalModule.getGlobalDependencies(),
                    securityLog);
            securityModule.setup();
            return securityModule;
        }
        return NoAuthSecurityProvider.INSTANCE;
    }

    /** {@inheritDoc} */
    @Override
    public void createDefaultDatabaseResolver(SystemDatabaseProvider systemDatabaseProvider) {
        var defaultDatabaseResolver =
                new CommunityDefaultDatabaseResolver(globalModule.getGlobalConfig(), systemDatabaseProvider);
        globalModule
                .getTransactionEventListeners()
                .registerTransactionEventListener(SYSTEM_DATABASE_NAME, defaultDatabaseResolver);
        this.defaultDatabaseResolver = defaultDatabaseResolver;
    }

    /** {@inheritDoc} */
    @Override
    public BoltGraphDatabaseManagementServiceSPI createBoltDatabaseManagementServiceProvider() {
        return globalModule.getGlobalDependencies().resolveDependency(BoltGraphDatabaseManagementServiceSPI.class);
    }

    /**
     * Creates the default transactional process factory for commit processing.
     *
     * @return the commit process factory
     */
    protected TransactionalProcessFactory createCommitProcessFactory() {
        return new DefaultTransactionalProcessFactory();
    }

    /** {@inheritDoc} */
    @Override
    public void bootstrapQueryRouterServices(DatabaseManagementService databaseManagementService) {
        DatabaseContextProvider<? extends DatabaseContext> databaseRepository =
                globalModule.getGlobalDependencies().resolveDependency(DatabaseContextProvider.class);

        var queryRouterBootstrap = new CommunityQueryRouterBootstrap(
                globalModule.getGlobalLife(),
                globalModule.getGlobalDependencies(),
                globalModule.getLogService(),
                databaseRepository,
                databaseReferenceRepo,
                CommunitySecurityLog.NULL_LOG);
        globalModule
                .getGlobalDependencies()
                .satisfyDependency(queryRouterBootstrap.bootstrapServices(databaseManagementService));
    }

    /** {@inheritDoc} */
    @Override
    public ReadOnlyDatabases readOnlyDatabases() {
        return globalReadOnlyChecker;
    }

    /** {@inheritDoc} */
    @Override
    public SystemGraphComponents getSystemGraphComponents() {
        return systemGraphComponents;
    }
}
