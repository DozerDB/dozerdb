/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.DetailLevel;
import org.dozerdb.querylog.extension.DynamicConfiguration;
import org.dozerdb.querylog.extension.QueryLogConfiguration;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.SettingChangeListener;
import org.neo4j.graphdb.config.Setting;

/**
 * Reads the eleven {@code db.logs.query.*} settings out of Neo4j's {@link Config} into a {@link QueryLogConfiguration}
 * and follows their changes. The settings are Community's own declarations in {@link GraphDatabaseSettings}, every one
 * of them dynamic, so the module declares none and the Operations Manual's description of each applies unchanged;
 * {@code db.track_query_cpu_time} is not read, because the query snapshot's CPU time is simply empty when it is off.
 *
 * <p>One {@link SettingChangeListener} per setting is added in {@link #register} and removed in {@link #unregister};
 * each change rebuilds the whole record from the configuration, which already holds the new value when a listener
 * runs, so the extension always sees a consistent set of eleven values. The two Neo4j enums are translated by name
 * into the module's own, so that no Neo4j type leaves this package.
 */
public final class ConfigurationAdapter implements DynamicConfiguration {

    private final Config config;
    private final List<Runnable> removals = new ArrayList<>();

    private ConfigurationAdapter(Config config) {
        this.config = config;
    }

    public static DynamicConfiguration of(Neo4jDependencies dependencies) {
        return new ConfigurationAdapter(dependencies.config());
    }

    @Override
    public QueryLogConfiguration read() {
        return new QueryLogConfiguration(
                detailLevel(config.get(GraphDatabaseSettings.log_queries)),
                config.get(GraphDatabaseSettings.log_queries_threshold),
                detailLevel(config.get(GraphDatabaseSettings.log_queries_transactions_level)),
                config.get(GraphDatabaseSettings.log_queries_transaction_threshold),
                config.get(GraphDatabaseSettings.log_queries_parameter_logging_enabled),
                config.get(GraphDatabaseSettings.query_log_max_parameter_length),
                config.get(GraphDatabaseSettings.log_queries_obfuscate_literals),
                config.get(GraphDatabaseSettings.log_queries_obfuscate_errors),
                config.get(GraphDatabaseSettings.log_queries_query_plan),
                config.get(GraphDatabaseSettings.log_queries_early_raw_logging_enabled),
                AnnotationDataFormat.valueOf(config.get(GraphDatabaseSettings.log_queries_annotation_data_format)
                        .name()));
    }

    @Override
    public void register(Consumer<QueryLogConfiguration> onChange) {
        follow(GraphDatabaseSettings.log_queries, onChange);
        follow(GraphDatabaseSettings.log_queries_threshold, onChange);
        follow(GraphDatabaseSettings.log_queries_transactions_level, onChange);
        follow(GraphDatabaseSettings.log_queries_transaction_threshold, onChange);
        follow(GraphDatabaseSettings.log_queries_parameter_logging_enabled, onChange);
        follow(GraphDatabaseSettings.query_log_max_parameter_length, onChange);
        follow(GraphDatabaseSettings.log_queries_obfuscate_literals, onChange);
        follow(GraphDatabaseSettings.log_queries_obfuscate_errors, onChange);
        follow(GraphDatabaseSettings.log_queries_query_plan, onChange);
        follow(GraphDatabaseSettings.log_queries_early_raw_logging_enabled, onChange);
        follow(GraphDatabaseSettings.log_queries_annotation_data_format, onChange);
    }

    @Override
    public void unregister() {
        removals.forEach(Runnable::run);
        removals.clear();
    }

    private <T> void follow(Setting<T> setting, Consumer<QueryLogConfiguration> onChange) {
        SettingChangeListener<T> listener = (before, after) -> onChange.accept(read());
        config.addListener(setting, listener);
        removals.add(() -> config.removeListener(setting, listener));
    }

    private static DetailLevel detailLevel(GraphDatabaseSettings.LogQueryLevel level) {
        return DetailLevel.valueOf(level.name());
    }
}
