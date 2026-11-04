/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.collectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * Server metrics (Operations Manual 2026.10, "Server metrics"): {@code neo4j.dbms.server.threads.jetty.all} and
 * {@code .idle}, the web server's thread pool. With the HTTP connector enabled the pool has threads; with the web
 * server disabled Neo4j's own stand-in reports a pool of zero threads, so the two gauges exist and read zero, and
 * nothing is logged as an error.
 */
class ServerThreadMetricsIT {

    private static final String ALL = "neo4j.metrics:name=neo4j.dbms.server.threads.jetty.all";
    private static final String IDLE = "neo4j.metrics:name=neo4j.dbms.server.threads.jetty.idle";

    @Test
    void withTheWebServerEnabledTheJettyPoolHasThreadsSomeOfThemIdle() {
        try (Neo4j neo4j = MetricsServers.startWithWebServer(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();

            long all = value(db, ALL);
            long idle = value(db, IDLE);

            assertThat(all).as("all threads").isPositive();
            assertThat(idle).as("idle threads").isBetween(0L, all);
        }
    }

    @Test
    void withTheWebServerDisabledBothGaugesReadZeroAndNothingIsLogged() throws IOException {
        try (Neo4j neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*")))) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();

            assertThat(value(db, ALL)).as("all threads").isZero();
            assertThat(value(db, IDLE)).as("idle threads").isZero();
            Path debugLog =
                    neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
            assertThat(Files.readAllLines(debugLog))
                    .as("no error about the server thread collector")
                    .noneMatch(line -> line.contains("ERROR") && line.contains("server.threads"));
        }
    }

    private static long value(GraphDatabaseService db, String objectName) {
        Object value = JmxQueries.value(db, objectName);
        assertThat(value).as("Value of " + objectName).isInstanceOf(Long.class);
        return (Long) value;
    }
}
