package org.neo4j.configuration;

import static org.neo4j.configuration.SettingConstraints.min;
import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.INT;

import org.neo4j.annotations.api.PublicApi;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.graphdb.config.Setting;

@ServiceProvider
@PublicApi
public class DozerDbSettings implements SettingsDeclaration {
    private static final Integer MINIMUM_DATABASES = 2;

    @Description(
            "The maximum number of databases that can be created in the system. The configuration option is the same for Neo4j Enterprise.  'dbms.max_databases'  The default is 100.")
    public static final Setting<Integer> max_databases = newBuilder("dbms.max_databases", INT, 100)
            .addConstraint(min(MINIMUM_DATABASES))
            .dynamic()
            .build();
}
