/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.adapter.MetricsInternalSettings;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * A collector that fails, while it is built, while it registers or while it is read, is logged once at error level
 * and disabled, and the server keeps serving with every other collector. Failing global and database collectors
 * are injected through the internal {@code additional_collectors} setting beside the built-in ones.
 */
class IsolationBoundaryIT {

    private static final String HEAP_USED = "neo4j.metrics:name=neo4j.dbms.vm.heap.used";
    private static Neo4j neo4j;
    private static GraphDatabaseService db;

    @BeforeAll
    static void startServerWithFailingCollectors() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(
                                MetricsInternalSettings.additional_collectors,
                                List.of(
                                        FailingAtRegistrationCollector.class.getName(),
                                        FailingAtReadCollector.class.getName(),
                                        FailingAtConstructionCollector.class.getName(),
                                        FailingAtConstructionDatabaseCollector.class.getName(),
                                        NotACollector.class.getName())));
        db = neo4j.defaultDatabaseService();
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void aCollectorFailingAtRegistrationIsDisabledAndLoggedOnceWhileTheServerServes() throws IOException {
        assertThat(JmxQueries.names(db, "neo4j.metrics:name=neo4j.dbms." + FailingAtRegistrationCollector.NAME + "*"))
                .as("the metrics it registered before failing are retired")
                .isEmpty();
        assertThat(JmxQueries.value(db, HEAP_USED))
                .as("other collectors are unaffected")
                .isNotNull();
        assertThat(serverServes()).isTrue();
        assertThat(errorLinesAbout(FailingAtRegistrationCollector.NAME)).hasSize(1);
    }

    @Test
    void aCollectorFailingWhileReportingIsDisabledAndLoggedOnceWhileTheServerServes() throws IOException {
        String failing = "neo4j.metrics:name=neo4j.dbms." + FailingAtReadCollector.NAME + ".value";
        String sibling = "neo4j.metrics:name=neo4j.dbms." + FailingAtReadCollector.NAME + ".sibling";
        assertThat(JmxQueries.value(db, sibling))
                .as("registered until the first failure")
                .isEqualTo(2L);

        assertThat(JmxQueries.value(db, failing))
                .as("the failing read reports no value")
                .isNull();
        JmxQueries.value(db, failing);

        awaitRetired("neo4j.metrics:name=neo4j.dbms." + FailingAtReadCollector.NAME + "*");
        assertThat(JmxQueries.value(db, HEAP_USED))
                .as("other collectors are unaffected")
                .isNotNull();
        assertThat(serverServes()).isTrue();
        assertThat(errorLinesAbout(FailingAtReadCollector.NAME)).hasSize(1);
    }

    /**
     * A global collector whose construction throws a linkage error, as one does when its Neo4j hook is missing, is
     * logged once under its class name; the built-in global collectors register as usual.
     */
    @Test
    void aGlobalCollectorFailingWhileItIsBuiltIsDisabledAndLoggedOnceWhileTheOthersRegister() throws IOException {
        assertThat(errorLinesAbout(FailingAtConstructionCollector.class.getName()))
                .hasSize(1);
        assertThat(JmxQueries.value(db, HEAP_USED)).isNotNull();
        assertThat(JmxQueries.names(db, "neo4j.metrics:name=neo4j.dbms.bolt.*"))
                .as("a built-in collector registered after the injected ones")
                .isNotEmpty();
        assertThat(serverServes()).isTrue();
    }

    /**
     * A database collector whose construction throws is logged once per database under the name
     * {@code database.<db>.<class name>}, and only that collector is lost: the database's built-in collectors register
     * and the database serves.
     */
    @Test
    void aDatabaseCollectorFailingWhileItIsBuiltLosesOnlyThatCollectorForEachDatabase() throws IOException {
        for (String database : List.of("system", "neo4j")) {
            assertThat(errorLinesAbout(
                            "database." + database + "." + FailingAtConstructionDatabaseCollector.class.getName()))
                    .as("one error line for " + database)
                    .hasSize(1);
            assertThat(JmxQueries.names(db, "neo4j.metrics:name=neo4j.database." + database + ".transaction.committed"))
                    .as("the transaction collector of " + database + " is registered")
                    .hasSize(1);
        }
        assertThat(serverServes()).isTrue();
    }

    /**
     * The setting names classes only: a class that is not a collector is rejected with one error line before it is
     * initialised, so naming an arbitrary class never runs its static initialiser.
     */
    @Test
    void aClassThatIsNotACollectorIsRejectedWithoutBeingInitialised() throws IOException {
        assertThat(errorLinesAbout(NotACollector.class.getName())).hasSize(1);
        assertThat(System.getProperty(NotACollector.INITIALISED_PROPERTY)).isNull();
    }

    private static boolean serverServes() {
        try (var tx = db.beginTx()) {
            return tx.execute("RETURN 1 AS one").next().get("one").equals(1L);
        }
    }

    /** Retirement of a collector that failed while being read happens off the reading thread. */
    private static void awaitRetired(String pattern) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!JmxQueries.names(db, pattern).isEmpty()) {
            assertThat(Instant.now())
                    .as("metrics of the failed collector are retired")
                    .isBefore(deadline);
            Thread.onSpinWait();
        }
    }

    private static List<String> errorLinesAbout(String component) throws IOException {
        Path debugLog = neo4j.config().get(GraphDatabaseSettings.logs_directory).resolve("debug.log");
        return Files.readAllLines(debugLog).stream()
                .filter(line -> line.contains("ERROR") && line.contains("'" + component + "'"))
                .toList();
    }
}
