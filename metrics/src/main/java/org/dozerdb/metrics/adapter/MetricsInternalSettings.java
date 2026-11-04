/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.STRING;
import static org.neo4j.configuration.SettingValueParsers.listOf;

import java.util.List;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.Description;
import org.neo4j.configuration.SettingsDeclaration;
import org.neo4j.graphdb.config.Setting;

/**
 * Internal settings of the metrics module: hidden from {@code SHOW SETTINGS} and the public API record, and
 * never part of the Neo4j-compatible surface.
 */
@ServiceProvider
public final class MetricsInternalSettings implements SettingsDeclaration {

    /**
     * The test-only seam for the isolation boundary. Each class named here is loaded without being initialised,
     * rejected unless it implements {@code Collector}, and started like a built-in collector: a database collector
     * when it has a public constructor taking {@code DatabaseDependencies}, a global one through its public
     * no-argument constructor otherwise. Tests inject collectors that fail while they are built, registered or read,
     * and observe that the server keeps serving.
     */
    @Description("Fully qualified class names of additional collectors to start with the built-in ones; each must "
            + "implement org.dozerdb.metrics.collectors.Collector and have a public constructor taking "
            + "org.dozerdb.metrics.adapter.DatabaseDependencies (a database collector) or none (a global collector). "
            + "A test-only seam for the isolation boundary.")
    public static final Setting<List<String>> additional_collectors = newBuilder(
                    "internal.dozerdb.metrics.additional_collectors", listOf(STRING), List.of())
            .internal()
            .build();
}
