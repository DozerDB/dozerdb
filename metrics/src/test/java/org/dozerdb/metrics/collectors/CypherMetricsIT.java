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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.dozerdb.metrics.Await;
import org.dozerdb.metrics.JmxQueries;
import org.dozerdb.metrics.MetricsServers;
import org.dozerdb.metrics.settings.MetricsSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.GlobbingPattern;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;

/**
 * The Cypher metrics (Operations Manual 2026.10, "Cypher metrics") as an administrator observes them:
 * {@code CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.cypher_cache_<kind>_<metric>")} and
 * {@code ...neo4j.database.neo4j.cypher.replan_events}. Each test runs real queries on the default database and
 * reads the metrics through the system database, whose own query caches are not the ones observed. The server
 * replans as soon as any statistic a plan used has changed ({@code dbms.cypher.statistics_divergence_threshold=0}
 * with no minimum interval), which is how the replan rows are provoked; every other test uses queries of its own
 * and never writes, so a replan never happens in it by accident.
 */
class CypherMetricsIT {

    private static final String OBJECT_NAME_PREFIX = "neo4j.metrics:name=neo4j.database.neo4j.";

    /** The four caches whose tracers Neo4j attaches with the default settings. */
    private static final List<String> TRACED_KINDS = List.of("preparser", "ast", "logical_plan", "executable_query");

    private static final List<String> ALL_KINDS =
            List.of("preparser", "ast", "logical_plan", "execution_plan", "executable_query");

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private static Neo4j neo4j;
    private static GraphDatabaseService db;
    private static GraphDatabaseService system;

    @BeforeAll
    static void startServerReplanningOnAnyStatisticsChange() {
        neo4j = MetricsServers.start(
                builder -> builder.withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                        .withConfig(GraphDatabaseSettings.query_statistics_divergence_threshold, 0.0)
                        .withConfig(GraphDatabaseSettings.cypher_min_replan_interval, Duration.ZERO));
        db = neo4j.defaultDatabaseService();
        system = neo4j.databaseManagementService().database("system");
    }

    @AfterAll
    static void stopServer() {
        if (neo4j != null) {
            neo4j.close();
        }
    }

    /**
     * A query that is run again with the same text and parameter types is served from the pre-parser cache and the
     * executable query cache; the caches in between are consulted only while a query is compiled.
     */
    @Test
    void runningTheSameQueryAgainIsAHitInThePreParserAndExecutableQueryCaches() {
        String query = uniqueQuery();
        run(query, Map.of("id", 1L));
        long preParserHits = count("cypher_cache_preparser_hits_total");
        long executableQueryHits = count("cypher_cache_executable_query_hits_total");

        run(query, Map.of("id", 2L));
        run(query, Map.of("id", 3L));

        assertThat(count("cypher_cache_preparser_hits_total"))
                .as("preparser hits")
                .isEqualTo(preParserHits + 2);
        assertThat(count("cypher_cache_executable_query_hits_total"))
                .as("executable_query hits")
                .isEqualTo(executableQueryHits + 2);
    }

    /**
     * A query never seen before misses every cache on its way to a plan and ends up as one more entry in each of the
     * five caches. Compiling it is not a recompilation with expression code generation, so no cache's
     * {@code compiled_total} moves (see {@link #aQueryRunPastTheRecompilationLimitIsRecompiledOnceWithExpressionCodeGeneration}).
     * The execution plan cache's other counters are untraced with the default settings (see
     * {@link #theExecutionPlanCacheCountersMoveOnlyWhenTheRuntimeMonitorsAreEnabled}), so only its entry count is
     * asserted here.
     */
    @Test
    void aQueryNeverSeenBeforeMissesEveryCacheAndAddsAnEntryToEach() {
        Map<String, Long> misses = snapshot("misses_total", TRACED_KINDS);
        Map<String, Long> entries = snapshot("entries", ALL_KINDS);
        Map<String, Long> compiled = snapshot("compiled_total", ALL_KINDS);

        run(uniqueQuery(), Map.of("id", 1L));
        run(uniqueQuery(), Map.of("id", 1L));

        for (String kind : TRACED_KINDS) {
            assertThat(count("cypher_cache_" + kind + "_misses_total"))
                    .as(kind + " misses")
                    .isEqualTo(misses.get(kind) + 2);
        }
        for (String kind : ALL_KINDS) {
            assertThat(gauge("cypher_cache_" + kind + "_entries"))
                    .as(kind + " entries")
                    .isEqualTo(entries.get(kind) + 2);
            assertThat(count("cypher_cache_" + kind + "_compiled_total"))
                    .as(kind + " compiled: a first compilation is not counted")
                    .isEqualTo(compiled.get(kind));
        }
    }

    /**
     * The manual's {@code compiled_total}: "the total number of times Neo4j replaced a query's plan with a more
     * optimized version that compiles expressions into executable code, after the query had been run repeatedly".
     * Neo4j does that in the executable query cache once a cached query has been hit
     * {@code internal.cypher.expression_recompilation_limit} times (10 by default), once per entry. The other four
     * caches never do it, so their counters stay zero.
     */
    @Test
    void aQueryRunPastTheRecompilationLimitIsRecompiledOnceWithExpressionCodeGeneration() {
        String query = uniqueQuery();
        long recompilations = count("cypher_cache_executable_query_compiled_total");

        for (int i = 0; i < 15; i++) {
            run(query, Map.of("id", (long) i));
        }

        assertThat(count("cypher_cache_executable_query_compiled_total"))
                .as("executable_query compiled after fifteen runs of one query")
                .isEqualTo(recompilations + 1);
        for (String kind : List.of("preparser", "ast", "logical_plan", "execution_plan")) {
            assertThat(count("cypher_cache_" + kind + "_compiled_total"))
                    .as(kind + " compiled: the cache never recompiles with expression code generation")
                    .isZero();
        }
    }

    /**
     * The logical plan cache is keyed by the parsed statement, not its text: a query that differs from a cached one
     * only in whitespace misses the text-keyed caches (pre-parser, AST, executable query) and hits the logical plan
     * cache.
     */
    @Test
    void aQueryDifferingOnlyInWhitespaceFromACachedOneHitsTheLogicalPlanCache() {
        String query = uniqueQuery();
        run(query, Map.of("id", 1L));
        long logicalPlanHits = count("cypher_cache_logical_plan_hits_total");
        long astMisses = count("cypher_cache_ast_misses_total");

        run(query.replace(" RETURN", "  RETURN"), Map.of("id", 1L));

        assertThat(count("cypher_cache_logical_plan_hits_total"))
                .as("logical_plan hits")
                .isEqualTo(logicalPlanHits + 1);
        assertThat(count("cypher_cache_ast_misses_total")).as("ast misses").isEqualTo(astMisses + 1);
    }

    /**
     * {@code CALL db.clearQueryCaches()} clears every cache of the database: each traced cache counts one flush and
     * discards the entries it held, and the entry counts fall (the procedure's own entry is cleared with the rest).
     */
    @Test
    void clearingTheQueryCachesCountsAFlushDiscardsTheEntriesAndEmptiesTheCaches() {
        run(uniqueQuery(), Map.of("id", 1L));
        run(uniqueQuery(), Map.of("id", 1L));
        Map<String, Long> flushes = snapshot("cache_flushes_total", TRACED_KINDS);
        Map<String, Long> discards = snapshot("discards_total", TRACED_KINDS);
        Map<String, Long> entries = snapshot("entries", ALL_KINDS);
        assertThat(entries.get("executable_query"))
                .as("entries before the flush")
                .isGreaterThan(1L);

        run("CALL db.clearQueryCaches()", Map.of());

        for (String kind : TRACED_KINDS) {
            assertThat(count("cypher_cache_" + kind + "_cache_flushes_total"))
                    .as(kind + " flushes")
                    .isEqualTo(flushes.get(kind) + 1);
            assertThat(count("cypher_cache_" + kind + "_discards_total"))
                    .as(kind + " discards")
                    .isGreaterThan(discards.get(kind));
        }
        for (String kind : ALL_KINDS) {
            assertThat(gauge("cypher_cache_" + kind + "_entries"))
                    .as(kind + " entries after the flush")
                    .isLessThan(entries.get(kind));
        }
    }

    /**
     * A cached plan is replanned when the statistics it used have diverged: here a label of ten nodes grows to thirty
     * between two runs of a query that scans it, more than a second after the plan was made (growing a label of one
     * node to two does not make Neo4j replan). Neo4j counts the replan
     * and the seconds the plan had lived, marks the executable query and the logical plan stale, and the recompile
     * is served the AST it parsed before. The other three caches never check staleness, so their counters stay zero.
     */
    @Test
    void aStatisticsChangeReplansTheQueryAndCountsTheReplanItsWaitTimeAndTheStaleEntries() throws Exception {
        String label = "Replan" + UNIQUE.incrementAndGet();
        createNodes(label, 10);
        String query = "MATCH (n:" + label + ") RETURN n";
        run(query, Map.of());
        long replans = count("cypher.replan_events");
        long waitTime = count("cypher.replan_wait_time");
        long executableQueryStale = count("cypher_cache_executable_query_stale_entries_total");
        long logicalPlanStale = count("cypher_cache_logical_plan_stale_entries_total");
        long astHits = count("cypher_cache_ast_hits_total");

        Thread.sleep(1100);
        createNodes(label, 20);
        run(query, Map.of());

        assertThat(count("cypher.replan_events")).as("replan_events").isEqualTo(replans + 1);
        assertThat(count("cypher.replan_wait_time"))
                .as("replan_wait_time in seconds")
                .isGreaterThanOrEqualTo(waitTime + 1);
        assertThat(count("cypher_cache_executable_query_stale_entries_total"))
                .as("executable_query stale entries")
                .isEqualTo(executableQueryStale + 1);
        assertThat(count("cypher_cache_logical_plan_stale_entries_total"))
                .as("logical_plan stale entries")
                .isEqualTo(logicalPlanStale + 1);
        assertThat(count("cypher_cache_ast_hits_total"))
                .as("ast hits from the recompile")
                .isEqualTo(astHits + 1);
        for (String kind : List.of("preparser", "ast", "execution_plan")) {
            assertThat(count("cypher_cache_" + kind + "_stale_entries_total"))
                    .as(kind + " stale entries: the cache has no staleness check")
                    .isZero();
        }
    }

    /**
     * Neo4j attaches the execution plan cache's metrics tracer only when {@code internal.cypher.enable_runtime_monitors}
     * is on; with the default settings its hits, misses, discards and flushes stay zero while its entries are
     * counted (above). On a server with the monitors enabled they move: a compile misses the cache, a compile of a
     * whitespace variant (same logical plan) hits it, and a flush counts. The server's caches hold two entries
     * ({@code server.db.query_cache_size=2}), so a third and fourth query evict entries from every cache into
     * {@code discards_total}, which the caches' maintenance reports asynchronously. The server has its own prefix
     * because the shared one is alive.
     */
    @Test
    void theExecutionPlanCacheCountersMoveOnlyWhenTheRuntimeMonitorsAreEnabled() {
        run(uniqueQuery(), Map.of("id", 1L));
        for (String metric : List.of("hits_total", "misses_total", "discards_total", "cache_flushes_total")) {
            assertThat(count("cypher_cache_execution_plan_" + metric))
                    .as("execution_plan " + metric + " with the default settings")
                    .isZero();
        }

        try (Neo4j traced = MetricsServers.start(builder -> builder.withConfig(MetricsSettings.metrics_prefix, "traced")
                .withConfig(MetricsSettings.metrics_filter, GlobbingPattern.create("*"))
                .withConfig(GraphDatabaseInternalSettings.cypher_enable_runtime_monitors, true)
                .withConfig(GraphDatabaseSettings.query_cache_size, 2))) {
            GraphDatabaseService tracedDb = traced.defaultDatabaseService();
            GraphDatabaseService tracedSystem =
                    traced.databaseManagementService().database("system");
            String prefix = "traced.metrics:name=traced.database.neo4j.cypher_cache_";
            String query = uniqueQuery();

            run(tracedDb, query, Map.of("id", 1L));
            long misses = countOf(tracedSystem, prefix + "execution_plan_misses_total");
            assertThat(misses).as("execution_plan misses after a compile").isGreaterThan(0L);
            long hits = countOf(tracedSystem, prefix + "execution_plan_hits_total");
            Map<String, Long> discards = ALL_KINDS.stream()
                    .collect(Collectors.toMap(
                            kind -> kind, kind -> countOf(tracedSystem, prefix + kind + "_discards_total")));

            run(tracedDb, query.replace(" RETURN", "  RETURN"), Map.of("id", 1L));
            assertThat(countOf(tracedSystem, prefix + "execution_plan_hits_total"))
                    .as("execution_plan hits from the whitespace variant")
                    .isEqualTo(hits + 1);

            run(tracedDb, uniqueQuery(), Map.of("id", 1L));
            run(tracedDb, uniqueQuery(), Map.of("id", 1L));
            for (String kind : ALL_KINDS) {
                Await.until(
                        "the " + kind + " cache of two entries evicts an entry",
                        () -> countOf(tracedSystem, prefix + kind + "_discards_total") > discards.get(kind));
            }

            long flushes = countOf(tracedSystem, prefix + "execution_plan_cache_flushes_total");
            run(tracedDb, "CALL db.clearQueryCaches()", Map.of());
            assertThat(countOf(tracedSystem, prefix + "execution_plan_cache_flushes_total"))
                    .as("execution_plan flushes")
                    .isEqualTo(flushes + 1);
        }
    }

    /**
     * With an open filter a database has exactly the manual's 37 Cypher metrics under its own segment: the two
     * {@code cypher.replan_*} counters and seven {@code cypher_cache_<kind>_*} metrics for each of the five cache
     * kinds, spelled as documented.
     */
    @Test
    void anOpenFilterAdmitsExactlyThe37DocumentedCypherMetricsOfADatabase() {
        List<String> expected = new ArrayList<>(
                List.of(OBJECT_NAME_PREFIX + "cypher.replan_events", OBJECT_NAME_PREFIX + "cypher.replan_wait_time"));
        for (String kind : ALL_KINDS) {
            for (String metric : List.of(
                    "cache_flushes_total",
                    "compiled_total",
                    "discards_total",
                    "entries",
                    "hits_total",
                    "misses_total",
                    "stale_entries_total")) {
                expected.add(OBJECT_NAME_PREFIX + "cypher_cache_" + kind + "_" + metric);
            }
        }

        assertThat(JmxQueries.names(system, OBJECT_NAME_PREFIX + "cypher*"))
                .hasSize(37)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Cypher metrics follow the database (Operations Manual 2026.10: "Database metrics are only available during the
     * lifetime of the database"): a created database brings its own replan counter and cache gauges, a dropped one
     * takes them away. DozerDB does not accept the {@code WAIT} clause, so the test polls {@code SHOW DATABASES}.
     */
    @Test
    void aCreatedDatabaseHasItsOwnCypherMetricsUntilItIsDropped() {
        String database = "planned";
        String objectNamePrefix = "neo4j.metrics:name=neo4j.database." + database + ".";
        execute(system, "CREATE DATABASE " + database);
        awaitStatus(database, Optional.of("online"));
        assertThat(JmxQueries.attribute(system, objectNamePrefix + "cypher.replan_events", "Count"))
                .as("cypher.replan_events of the created database")
                .isInstanceOf(Long.class);
        assertThat(JmxQueries.value(system, objectNamePrefix + "cypher_cache_executable_query_entries"))
                .as("cypher_cache_executable_query_entries of the created database")
                .isInstanceOf(Long.class);

        execute(system, "DROP DATABASE " + database);
        awaitStatus(database, Optional.empty());
        assertThat(JmxQueries.names(system, "neo4j.metrics:name=neo4j.database." + database + ".*"))
                .as("metrics of the dropped database")
                .isEmpty();
    }

    /** A read query no earlier test has run, so its first run misses every cache. */
    private static String uniqueQuery() {
        return "MATCH (n:Unique" + UNIQUE.incrementAndGet() + ") WHERE n.id = $id RETURN n";
    }

    private static void run(String cypher, Map<String, Object> parameters) {
        run(db, cypher, parameters);
    }

    private static void run(GraphDatabaseService database, String cypher, Map<String, Object> parameters) {
        try (Transaction tx = database.beginTx()) {
            tx.execute(cypher, parameters).close();
            tx.commit();
        }
    }

    private static void createNodes(String label, int count) {
        try (Transaction tx = db.beginTx()) {
            for (int i = 0; i < count; i++) {
                tx.createNode(Label.label(label));
            }
            tx.commit();
        }
    }

    private static void execute(GraphDatabaseService database, String cypher) {
        try (Transaction tx = database.beginTx()) {
            tx.execute(cypher).close();
            tx.commit();
        }
    }

    private static void awaitStatus(String database, Optional<String> status) {
        Await.until(database + " has status " + status.orElse("<absent>"), () -> {
            try (Transaction tx = system.beginTx()) {
                return tx
                        .execute(
                                "SHOW DATABASES YIELD name, currentStatus WHERE name = $name RETURN currentStatus",
                                Map.of("name", database))
                        .stream()
                        .map(row -> (String) row.get("currentStatus"))
                        .findFirst()
                        .equals(status);
            }
        });
    }

    private static Map<String, Long> snapshot(String metric, List<String> kinds) {
        return kinds.stream()
                .collect(Collectors.toMap(
                        kind -> kind,
                        kind -> metric.equals("entries")
                                ? gauge("cypher_cache_" + kind + "_entries")
                                : count("cypher_cache_" + kind + "_" + metric)));
    }

    /** The {@code Value} attribute of a gauge of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long gauge(String metric) {
        Object value = JmxQueries.value(system, OBJECT_NAME_PREFIX + metric);
        assertThat(value).as("Value of " + metric).isInstanceOf(Long.class);
        return (Long) value;
    }

    /** The {@code Count} attribute of a counter of the default database, as cypher-shell {@code :sysinfo} reads it. */
    private static long count(String metric) {
        return countOf(system, OBJECT_NAME_PREFIX + metric);
    }

    private static long countOf(GraphDatabaseService reader, String objectName) {
        Object count = JmxQueries.attribute(reader, objectName, "Count");
        assertThat(count).as("Count of " + objectName).isInstanceOf(Long.class);
        return (Long) count;
    }
}
