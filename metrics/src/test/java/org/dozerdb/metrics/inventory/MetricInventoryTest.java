/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.dozerdb.metrics.inventory.InventoryEntry.FormatProvenance;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;
import org.dozerdb.metrics.inventory.InventoryEntry.Scope;
import org.dozerdb.metrics.inventory.InventoryEntry.Tier;
import org.dozerdb.metrics.inventory.InventoryEntry.TypeRule;
import org.junit.jupiter.api.Test;

/**
 * The shipped inventory against the Operations Manual 5 metrics reference, counted section by section from
 * the page: all 249 documented metrics in the page's sections and order, the 66 clustering metrics Not applicable, the deprecated flags, and the tiers
 * as they stand. A collector that starts emitting rows changes {@link #COMPATIBLE} here and nothing else.
 */
class MetricInventoryTest {

    private static final String REFERENCE_PAGE =
            "https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/";

    /** Section, its anchor on the reference page and its number of metrics, in page order. */
    private static final Map<String, Section> SECTIONS = sections(
            "Bolt metrics",
            "#bolt-metrics",
            14,
            "Bolt Driver metrics",
            "#bolt-driver-metrics",
            4,
            "Cypher metrics",
            "#cypher-metrics",
            37,
            "Database checkpointing metrics",
            "#checkpointing-metrics",
            9,
            "Database data count metrics",
            "#db-data-count-metrics",
            3,
            "Database neo4j pools metrics",
            "#db-neo4j-pool-metrics",
            5,
            "Database operation count metrics",
            "#db-operation-count-metrics",
            6,
            "Database state count metrics",
            "#db-state-count-metrics",
            3,
            "Database data metrics",
            "#db-data-metrics",
            4,
            "Global neo4j pools metrics",
            "#global-neo4j-pools-metrics",
            5,
            "Database page cache metrics",
            "#db-page-cache-metrics",
            24,
            "Query execution metrics",
            "#query-execution-metrics",
            12,
            "Query routing metrics",
            "#query-routing-metrics",
            3,
            "Database store size metrics",
            "#db-store-size-metrics",
            3,
            "Database transaction log metrics",
            "#db-tx-log-metrics",
            6,
            "Database transaction metrics",
            "#db-tx-metrics",
            18,
            "Database index metrics",
            "#db-index-metrics",
            12,
            "Server metrics",
            "#server-metrics",
            2,
            "Catch-up metrics",
            "#catchup-metrics",
            1,
            "Discovery metrics v1",
            "#discovery-service-V1",
            6,
            "Discovery metrics v2",
            "#discovery-service-V2",
            2,
            "Raft core metrics",
            "#raft-core-metrics",
            25,
            "Raft metrics",
            "#raft-metrics",
            26,
            "Read Replica metrics",
            "#read-replica-metrics",
            3,
            "Store copy metrics",
            "#store-copy-metrics",
            3,
            "JVM file descriptor metrics",
            "#jvm-file-descriptor-metrics",
            2,
            "GC metrics",
            "#gc-metrics",
            2,
            "JVM Heap metrics",
            "#jvm-heap-metrics",
            3,
            "JVM memory buffers metrics",
            "#jvm-memory-buffers-metrics",
            3,
            "JVM memory pools metrics",
            "#jvm-memory-pools-metrics",
            1,
            "JVM pause time metrics",
            "#jvm-pause-time-metrics",
            1,
            "JVM threads metrics",
            "#jvm-threads-metrics",
            1);

    private static final Set<String> CLUSTERING_SECTIONS = Set.of(
            "Catch-up metrics",
            "Discovery metrics v1",
            "Discovery metrics v2",
            "Raft core metrics",
            "Raft metrics",
            "Read Replica metrics",
            "Store copy metrics");

    /** The sections the 5 page labels deprecated as a whole (the clustering tables kept from Neo4j 4 and 5.0). */
    private static final Set<String> DEPRECATED_SECTIONS =
            Set.of("Discovery metrics v1", "Raft core metrics", "Read Replica metrics");

    /** Why every histogram is Partial until an Enterprise capture settles the distribution semantics. */
    private static final String RESERVOIR_REASON =
            "Dropwizard default reservoir; distribution semantics not established against Enterprise";

    /** The metrics DozerDB emits as Compatible; a collector that flips a row extends this list. */
    private static final List<String> COMPATIBLE = List.of(
            "<prefix>.dbms.bolt.connections_opened",
            "<prefix>.dbms.bolt.connections_closed",
            "<prefix>.dbms.bolt.connections_running",
            "<prefix>.dbms.bolt.connections_idle",
            "<prefix>.dbms.bolt.messages_received",
            "<prefix>.dbms.bolt.messages_started",
            "<prefix>.dbms.bolt.messages_done",
            "<prefix>.dbms.bolt.messages_failed",
            "<prefix>.dbms.bolt.accumulated_queue_time",
            "<prefix>.dbms.bolt.accumulated_processing_time",
            "<prefix>.dbms.bolt_driver.api.managed_transaction_function_calls",
            "<prefix>.dbms.bolt_driver.api.unmanaged_transaction_calls",
            "<prefix>.dbms.bolt_driver.api.implicit_transaction_calls",
            "<prefix>.dbms.bolt_driver.api.execute_calls",
            "<prefix>.vm.heap.committed",
            "<prefix>.vm.heap.used",
            "<prefix>.vm.heap.max",
            "<prefix>.vm.gc.time.<gc>",
            "<prefix>.vm.gc.count.<gc>",
            "<prefix>.vm.memory.pool.<pool>",
            "<prefix>.vm.memory.buffer.<bufferpool>.count",
            "<prefix>.vm.memory.buffer.<bufferpool>.used",
            "<prefix>.vm.memory.buffer.<bufferpool>.capacity",
            "<prefix>.vm.threads",
            "<prefix>.vm.file.descriptors.count",
            "<prefix>.vm.file.descriptors.maximum",
            "<prefix>.vm.pause_time",
            "<prefix>.dbms.pool.<pool>.used_heap",
            "<prefix>.dbms.pool.<pool>.used_native",
            "<prefix>.dbms.pool.<pool>.total_used",
            "<prefix>.dbms.pool.<pool>.total_size",
            "<prefix>.dbms.pool.<pool>.free",
            "<prefix>.database.<db>.pool.<pool>.<db>.used_heap",
            "<prefix>.database.<db>.pool.<pool>.<db>.used_native",
            "<prefix>.database.<db>.pool.<pool>.<db>.total_used",
            "<prefix>.database.<db>.pool.<pool>.<db>.total_size",
            "<prefix>.database.<db>.pool.<pool>.<db>.free",
            "<prefix>.server.threads.jetty.idle",
            "<prefix>.server.threads.jetty.all",
            "<prefix>.transaction.started",
            "<prefix>.transaction.peak_concurrent",
            "<prefix>.transaction.active",
            "<prefix>.transaction.active_read",
            "<prefix>.transaction.active_write",
            "<prefix>.transaction.committed",
            "<prefix>.transaction.committed_read",
            "<prefix>.transaction.committed_write",
            "<prefix>.transaction.rollbacks",
            "<prefix>.transaction.rollbacks_read",
            "<prefix>.transaction.rollbacks_write",
            "<prefix>.transaction.terminated",
            "<prefix>.transaction.terminated_read",
            "<prefix>.transaction.terminated_write",
            "<prefix>.transaction.last_committed_tx_id",
            "<prefix>.transaction.last_closed_tx_id",
            "<prefix>.check_point.events",
            "<prefix>.check_point.total_time",
            "<prefix>.check_point.duration",
            "<prefix>.check_point.flushed_bytes",
            "<prefix>.check_point.pages_flushed",
            "<prefix>.check_point.io_performed",
            "<prefix>.log.rotation_events",
            "<prefix>.log.rotation_total_time",
            "<prefix>.log.rotation_duration",
            "<prefix>.log.appended_bytes",
            "<prefix>.log.flushes",
            "<prefix>.log.append_batch_size",
            "<prefix>.page_cache.flushes",
            "<prefix>.page_cache.merges",
            "<prefix>.page_cache.unpins",
            "<prefix>.page_cache.pins",
            "<prefix>.page_cache.evictions",
            "<prefix>.page_cache.evictions.cooperative",
            "<prefix>.page_cache.eviction.flushes",
            "<prefix>.page_cache.eviction.cooperative.flushes",
            "<prefix>.page_cache.page_faults",
            "<prefix>.page_cache.hits",
            "<prefix>.page_cache.hit_ratio",
            "<prefix>.page_cache.usage_ratio",
            "<prefix>.page_cache.bytes_read",
            "<prefix>.page_cache.bytes_written",
            "<prefix>.page_cache.iops",
            "<prefix>.neo4j.count.relationship",
            "<prefix>.neo4j.count.node",
            "<prefix>.neo4j.count.relationship_types",
            "<prefix>.ids_in_use.relationship_type",
            "<prefix>.ids_in_use.property",
            "<prefix>.ids_in_use.relationship",
            "<prefix>.ids_in_use.node",
            "<prefix>.store.size.total",
            "<prefix>.store.size.database",
            "<prefix>.store.size.available_reserved",
            "<prefix>.db.operation.count.create",
            "<prefix>.db.operation.count.start",
            "<prefix>.db.operation.count.drop",
            "<prefix>.db.state.count.hosted",
            "<prefix>.db.state.count.failed",
            "<prefix>.db.state.count.desired_started",
            "<prefix>.cypher.replan_events",
            "<prefix>.cypher.replan_wait_time",
            "<prefix>.cypher_cache_ast_cache_flushes_total",
            "<prefix>.cypher_cache_ast_discards_total",
            "<prefix>.cypher_cache_ast_entries",
            "<prefix>.cypher_cache_ast_hits_total",
            "<prefix>.cypher_cache_ast_misses_total",
            "<prefix>.cypher_cache_executable_query_cache_flushes_total",
            "<prefix>.cypher_cache_executable_query_discards_total",
            "<prefix>.cypher_cache_executable_query_entries",
            "<prefix>.cypher_cache_executable_query_hits_total",
            "<prefix>.cypher_cache_executable_query_misses_total",
            "<prefix>.cypher_cache_logical_plan_cache_flushes_total",
            "<prefix>.cypher_cache_logical_plan_discards_total",
            "<prefix>.cypher_cache_logical_plan_entries",
            "<prefix>.cypher_cache_logical_plan_hits_total",
            "<prefix>.cypher_cache_logical_plan_misses_total",
            "<prefix>.cypher_cache_preparser_cache_flushes_total",
            "<prefix>.cypher_cache_preparser_discards_total",
            "<prefix>.cypher_cache_preparser_entries",
            "<prefix>.cypher_cache_preparser_hits_total",
            "<prefix>.cypher_cache_preparser_misses_total",
            "<prefix>.cypher_cache_execution_plan_entries",
            "<prefix>.cypher_cache_executable_query_compiled_total",
            "<prefix>.cypher_cache_executable_query_stale_entries_total",
            "<prefix>.cypher_cache_logical_plan_stale_entries_total",
            "<prefix>.index.fulltext.queried",
            "<prefix>.index.fulltext.populated",
            "<prefix>.index.lookup.queried",
            "<prefix>.index.lookup.populated",
            "<prefix>.index.text.queried",
            "<prefix>.index.text.populated",
            "<prefix>.index.range.queried",
            "<prefix>.index.range.populated",
            "<prefix>.index.point.queried",
            "<prefix>.index.point.populated",
            "<prefix>.index.vector.queried",
            "<prefix>.index.vector.populated",
            "<prefix>.db.query.execution.success",
            "<prefix>.db.query.execution.failure",
            "<prefix>.db.query.execution.slotted.success",
            "<prefix>.db.query.execution.slotted.failure",
            "<prefix>.dbms.routing.query.count.local");

    /** Why a page cache failure counter is Partial: Neo4j counts it, but no public API makes a page cache fail. */
    private static final String UNPROVEN_FAILURE_REASON =
            "Reads Neo4j's own failure counter; no test provokes a page cache IO failure, so the measured behaviour is unproven";

    /** Why a page cache counter whose event only an Enterprise mechanism produces is Partial. */
    private static final String NO_FAULT_CURSORS_REASON =
            "Counts pins of cursors opened with the no-fault flag; Community opens none, so the counter stays zero";

    private static final String PREFETCHER_REASON =
            "Counts the page prefetcher's vectored faults; Community's prefetcher is disabled, so the counter stays zero";
    private static final String IO_LIMITER_REASON =
            "Counts checkpoint IO limiter throttling; Community has no IO limiter, so the counter stays zero";

    /** Why the three checkpoint IO limiter gauges are Partial: the limiter is Enterprise-only. */
    private static final String CHECKPOINT_IO_LIMITER_REASON =
            "Reads the checkpoint IO limiter's limit and pauses of the last checkpoint; Community has no IO limiter, so the value stays zero";

    private static final String PAGE_COPIES_REASON =
            "Counts multi-version page copies; Community's stores are single-version, so the counter stays zero";

    /** Why the two database operation counts no public event or API can prove are Partial. */
    private static final String PANIC_ONLY_REASON =
            "Counts database panics, the one failure the public database events report; a failed start or stop raises no event and no test provokes a panic, so the measured behaviour is unproven";

    /** Why the stop count is Partial: a drop's stop cannot be told apart from STOP DATABASE in the public events. */
    private static final String STOP_INCLUDES_DROP_REASON =
            "A DROP DATABASE of an online database counts one stop as well as one drop, because Neo4j stops the database before dropping it and announces that stop with the same event as STOP DATABASE; whether Neo4j counts it as a stop is not established";

    private static final String RECOVERED_AFTER_PANIC_REASON =
            "Counts the next successful start of a database whose panic was counted as failed; no test provokes a panic, so the measured behaviour is unproven";

    /** Why the three Bolt response rows count only under an internal Neo4j setting. */
    private static final String RESPONSE_METRICS_REASON = "Counts only with the internal setting "
            + "internal.server.bolt.response_metrics=true, which installs Neo4j's response metrics listener; "
            + "0 under default settings";

    /** Why a query execution series of a runtime Community lacks is Partial: it stays zero. */
    private static String absentRuntimeCounterReason(String runtime) {
        return "Counts queries executed by the " + runtime + " runtime; Community has no " + runtime
                + " runtime, so the counter stays zero";
    }

    private static String absentRuntimeHistogramReason(String runtime) {
        return RESERVOIR_REASON + "; Community has no " + runtime + " runtime, so the histogram stays empty";
    }

    /** Why a Cypher cache's compiled_total is Partial: only the executable query cache recompiles. */
    private static final String NEVER_RECOMPILED_REASON =
            "Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven";

    /** Why a Cypher cache's stale_entries_total is Partial: only the two plan caches check staleness. */
    private static final String NO_STALENESS_REASON =
            "Neo4j checks staleness only in the executable query and logical plan caches, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven";

    /** Why four execution plan cache counters count only under an internal Neo4j setting. */
    private static final String EXECUTION_PLAN_TRACER_REASON = "Counts only with the internal setting "
            + "internal.cypher.enable_runtime_monitors=true, which attaches Neo4j's execution plan cache tracer; "
            + "0 under default settings";

    /** The metrics DozerDB emits with a recorded difference, and the difference. */
    private static final Map<String, String> PARTIAL = Map.ofEntries(
            Map.entry("<prefix>.dbms.bolt.worker_thread_bound_time", RESERVOIR_REASON),
            Map.entry("<prefix>.dbms.bolt.response_success", RESPONSE_METRICS_REASON),
            Map.entry("<prefix>.dbms.bolt.response_ignored", RESPONSE_METRICS_REASON),
            Map.entry(
                    "<prefix>.dbms.bolt.response_failed",
                    RESPONSE_METRICS_REASON + "; one counter over every error code"),
            Map.entry("<prefix>.transaction.tx_size_heap", RESERVOIR_REASON),
            Map.entry("<prefix>.transaction.tx_size_native", RESERVOIR_REASON),
            Map.entry("<prefix>.db.query.execution.latency.millis", RESERVOIR_REASON),
            Map.entry("<prefix>.db.query.execution.slotted.latency.millis", RESERVOIR_REASON),
            Map.entry("<prefix>.db.query.execution.parallel.success", absentRuntimeCounterReason("parallel")),
            Map.entry("<prefix>.db.query.execution.parallel.failure", absentRuntimeCounterReason("parallel")),
            Map.entry("<prefix>.db.query.execution.parallel.latency.millis", absentRuntimeHistogramReason("parallel")),
            Map.entry("<prefix>.db.query.execution.pipelined.success", absentRuntimeCounterReason("pipelined")),
            Map.entry("<prefix>.db.query.execution.pipelined.failure", absentRuntimeCounterReason("pipelined")),
            Map.entry(
                    "<prefix>.db.query.execution.pipelined.latency.millis", absentRuntimeHistogramReason("pipelined")),
            Map.entry(
                    "<prefix>.dbms.routing.query.count.remote_internal",
                    "Counts queries routed to another member of the same cluster; DozerDB runs as a single instance, "
                            + "so the counter stays zero"),
            Map.entry(
                    "<prefix>.dbms.routing.query.count.remote_external",
                    "Counts queries routed to a remote database alias; DozerDB has no remote database aliases, "
                            + "so the counter stays zero"),
            Map.entry("<prefix>.page_cache.eviction_exceptions", UNPROVEN_FAILURE_REASON),
            Map.entry("<prefix>.page_cache.page_fault_failures", UNPROVEN_FAILURE_REASON),
            Map.entry("<prefix>.page_cache.page_cancelled_faults", NO_FAULT_CURSORS_REASON),
            Map.entry("<prefix>.page_cache.page_vectored_faults", PREFETCHER_REASON),
            Map.entry("<prefix>.page_cache.page_vectored_faults_failures", PREFETCHER_REASON),
            Map.entry("<prefix>.page_cache.page_no_pin_page_faults", PREFETCHER_REASON),
            Map.entry("<prefix>.page_cache.throttled.times", IO_LIMITER_REASON),
            Map.entry("<prefix>.page_cache.throttled.millis", IO_LIMITER_REASON),
            Map.entry("<prefix>.page_cache.pages_copied", PAGE_COPIES_REASON),
            Map.entry("<prefix>.check_point.io_limit", CHECKPOINT_IO_LIMITER_REASON),
            Map.entry("<prefix>.check_point.limit_times", CHECKPOINT_IO_LIMITER_REASON),
            Map.entry("<prefix>.check_point.limit_millis", CHECKPOINT_IO_LIMITER_REASON),
            Map.entry("<prefix>.db.operation.count.stop", STOP_INCLUDES_DROP_REASON),
            Map.entry("<prefix>.db.operation.count.failed", PANIC_ONLY_REASON),
            Map.entry("<prefix>.db.operation.count.recovered", RECOVERED_AFTER_PANIC_REASON),
            Map.entry("<prefix>.cypher_cache_ast_compiled_total", NEVER_RECOMPILED_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_compiled_total", NEVER_RECOMPILED_REASON),
            Map.entry("<prefix>.cypher_cache_logical_plan_compiled_total", NEVER_RECOMPILED_REASON),
            Map.entry("<prefix>.cypher_cache_preparser_compiled_total", NEVER_RECOMPILED_REASON),
            Map.entry("<prefix>.cypher_cache_ast_stale_entries_total", NO_STALENESS_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_stale_entries_total", NO_STALENESS_REASON),
            Map.entry("<prefix>.cypher_cache_preparser_stale_entries_total", NO_STALENESS_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_cache_flushes_total", EXECUTION_PLAN_TRACER_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_discards_total", EXECUTION_PLAN_TRACER_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_hits_total", EXECUTION_PLAN_TRACER_REASON),
            Map.entry("<prefix>.cypher_cache_execution_plan_misses_total", EXECUTION_PLAN_TRACER_REASON));

    private final MetricInventory inventory = MetricInventory.load();

    @Test
    void theInventoryListsAll249DocumentedMetrics() {
        assertThat(inventory.entries()).hasSize(249);
    }

    @Test
    void theSectionsAreThePagesSectionsInPageOrderWithThePagesCounts() {
        assertThat(inventory.sections()).containsExactlyElementsOf(SECTIONS.keySet());
        Map<String, Long> counts = inventory.entries().stream()
                .collect(Collectors.groupingBy(InventoryEntry::section, LinkedHashMap::new, Collectors.counting()));
        SECTIONS.forEach((section, expected) ->
                assertThat(counts.get(section)).as(section).isEqualTo((long) expected.count()));
    }

    @Test
    void everyRowLinksToItsSectionOnThePinnedReferencePage() {
        assertThat(inventory.entries()).allSatisfy(entry -> assertThat(entry.docsAnchor())
                .as(entry.nameTemplate())
                .isEqualTo(REFERENCE_PAGE + SECTIONS.get(entry.section()).anchor()));
    }

    @Test
    void theClusteringSectionsAreExactlyTheNotApplicableMetrics() {
        List<InventoryEntry> notApplicable = inventory.entries().stream()
                .filter(entry -> entry.tier() == Tier.NOT_APPLICABLE)
                .toList();

        assertThat(notApplicable).hasSize(66);
        assertThat(notApplicable)
                .allSatisfy(entry -> assertThat(entry.section()).isIn(CLUSTERING_SECTIONS));
        assertThat(inventory.entries())
                .filteredOn(entry -> CLUSTERING_SECTIONS.contains(entry.section()))
                .allSatisfy(entry -> assertThat(entry.tier()).isEqualTo(Tier.NOT_APPLICABLE));
    }

    @Test
    void theEmittedMetricsAreExactlyTheCompatibleAndPartialRowsListedHere() {
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.tier() == Tier.COMPATIBLE)
                .extracting(InventoryEntry::nameTemplate)
                .containsExactlyInAnyOrderElementsOf(COMPATIBLE);
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.tier() == Tier.PARTIAL)
                .extracting(InventoryEntry::nameTemplate, InventoryEntry::reason)
                .containsExactlyInAnyOrderElementsOf(PARTIAL.entrySet().stream()
                        .map(partial -> tuple(partial.getKey(), partial.getValue()))
                        .toList());
    }

    /** A histogram may add a second difference after the reservoir reason (a runtime Community lacks). */
    @Test
    void everyEmittedHistogramIsPartialForItsReservoir() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.type() == MetricType.HISTOGRAM)
                .isNotEmpty()
                .allSatisfy(entry -> {
                    assertThat(entry.tier()).as(entry.nameTemplate()).isEqualTo(Tier.PARTIAL);
                    assertThat(entry.reason()).as(entry.nameTemplate()).startsWith(RESERVOIR_REASON);
                });
    }

    /**
     * Every row the 5 reference lists is emitted or is clustering: the rows a later manual adds that DozerDB cannot
     * produce ({@code vm.threads.virtual}) are not on this page, so no row is Unsupported.
     */
    @Test
    void noRowIsUnsupported() {
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.tier() == Tier.UNSUPPORTED)
                .isEmpty();
    }

    @Test
    void theHeapGaugesAreGlobalGaugesInBytesEstablishedByCommunitySource() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("JVM Heap metrics"))
                .hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.type()).isEqualTo(MetricType.GAUGE);
                    assertThat(entry.unit()).isEqualTo("bytes");
                    assertThat(entry.scope()).isEqualTo(Scope.GLOBAL);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                });
    }

    @Test
    void theEmittedTransactionRowsAreDatabaseMetricsEstablishedByCommunitySource() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Database transaction metrics"))
                .isNotEmpty()
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("TransactionCollector");
                });
    }

    /**
     * All 24 page cache rows are emitted as global metrics; the four cypher-shell {@code :sysinfo} reads are
     * established by Community source, the rest by the manual alone.
     */
    @Test
    void thePageCacheRowsAreAllEmittedAsGlobalMetricsNamingTheCollector() {
        List<InventoryEntry> pageCache = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Database page cache metrics"))
                .toList();

        assertThat(pageCache).hasSize(24).allSatisfy(entry -> {
            assertThat(entry.scope()).as(entry.nameTemplate()).isEqualTo(Scope.GLOBAL);
            assertThat(entry.notes()).as(entry.nameTemplate()).contains("PageCacheCollector");
        });
        assertThat(pageCache)
                .filteredOn(entry -> entry.formatProvenance() == FormatProvenance.COMMUNITY_SOURCE)
                .extracting(InventoryEntry::nameTemplate)
                .containsExactlyInAnyOrder(
                        "<prefix>.page_cache.hits",
                        "<prefix>.page_cache.hit_ratio",
                        "<prefix>.page_cache.usage_ratio",
                        "<prefix>.page_cache.page_faults");
        assertThat(pageCache)
                .filteredOn(entry -> entry.formatProvenance() != FormatProvenance.COMMUNITY_SOURCE)
                .allSatisfy(entry -> assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.DOCUMENTED));
    }

    /**
     * All nine database operation and state count rows are emitted as global metrics kept by DozerDB itself from
     * the database events and states; Community's counters are declared but never driven, and never read.
     */
    @Test
    void theDatabaseCountRowsAreAllEmittedAsGlobalMetricsKeptByDozerDbItself() {
        List<InventoryEntry> counts = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Database operation count metrics")
                        || entry.section().equals("Database state count metrics"))
                .toList();

        assertThat(counts).hasSize(9).allSatisfy(entry -> {
            assertThat(entry.scope()).as(entry.nameTemplate()).isEqualTo(Scope.GLOBAL);
            assertThat(entry.formatProvenance()).as(entry.nameTemplate()).isEqualTo(FormatProvenance.DOCUMENTED);
            assertThat(entry.notes())
                    .as(entry.nameTemplate())
                    .contains("DatabaseCountsCollector")
                    .contains("not read");
        });
    }

    @Test
    void theEmittedCheckpointAndTransactionLogRowsAreDatabaseMetricsEstablishedByCommunitySource() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Database checkpointing metrics"))
                .hasSize(9)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("CheckpointCollector", "CheckpointCounters.");
                });
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Database transaction log metrics"))
                .hasSize(6)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("TransactionLogCollector", "TransactionLogCounters.");
                });
    }

    @Test
    void theEmittedBoltRowsAreGlobalMetricsEstablishedByCommunitySource() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Bolt metrics"))
                .hasSize(14)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).isEqualTo(Scope.GLOBAL);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("BoltCollector");
                });
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Bolt Driver metrics"))
                .hasSize(4)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).isEqualTo(Scope.GLOBAL);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("BoltDriverCollector").contains("server.bolt.telemetry.enabled");
                });
    }

    /**
     * All twelve query execution rows are database metrics fed by the one query execution collector; no
     * {@code :sysinfo} client reads them, so the manual alone establishes their format.
     */
    @Test
    void theQueryExecutionRowsAreDatabaseMetricsEstablishedByTheManual() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Query execution metrics"))
                .hasSize(12)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).as(entry.nameTemplate()).isEqualTo(Scope.DATABASE);
                    assertThat(entry.formatProvenance())
                            .as(entry.nameTemplate())
                            .isEqualTo(FormatProvenance.DOCUMENTED);
                    assertThat(entry.notes()).as(entry.nameTemplate()).contains("QueryExecutionCollector");
                });
    }

    /**
     * The three query routing rows are global counters of the query routing collector, emitted together although a
     * single instance only ever routes locally.
     */
    @Test
    void theQueryRoutingRowsAreGlobalCountersEstablishedByTheManual() {
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Query routing metrics"))
                .hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.scope()).as(entry.nameTemplate()).isEqualTo(Scope.GLOBAL);
                    assertThat(entry.type()).as(entry.nameTemplate()).isEqualTo(MetricType.COUNTER);
                    assertThat(entry.formatProvenance())
                            .as(entry.nameTemplate())
                            .isEqualTo(FormatProvenance.DOCUMENTED);
                    assertThat(entry.notes()).as(entry.nameTemplate()).contains("QueryRoutingCollector");
                });
    }

    @Test
    void deprecatedMetricsAreTheIdsInUseGaugesAndTheClusteringSectionsThePageDeprecates() {
        assertThat(inventory.entries())
                .filteredOn(InventoryEntry::deprecated)
                .allSatisfy(entry -> assertThat(entry.nameTemplate().startsWith("<prefix>.ids_in_use.")
                                || DEPRECATED_SECTIONS.contains(entry.section()))
                        .as(entry.nameTemplate())
                        .isTrue())
                .hasSize(4 + 6 + 25 + 3);
        assertThat(inventory.entries()).allSatisfy(entry -> assertThat(entry.deprecated())
                .as(entry.nameTemplate())
                .isEqualTo(entry.lifecycle().startsWith("Deprecated")));
    }

    @Test
    void theDocumentedTypeDecidesExceptForTheTwoRaftTimersTheManualTypesAsCounterAndHistogram() {
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.typeRule() == TypeRule.ORACLE)
                .extracting(InventoryEntry::nameTemplate)
                .containsExactlyInAnyOrder(
                        "<prefix>.causal_clustering.core.message_processing_timer",
                        "<prefix>.cluster.raft.message_processing_timer");
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.type() == MetricType.TIMER)
                .allSatisfy(entry -> assertThat(entry.typeRule()).isEqualTo(TypeRule.ORACLE));
    }

    @Test
    void typesFollowTheManualsMarkers() {
        Map<MetricType, Long> counts = inventory.entries().stream()
                .collect(Collectors.groupingBy(InventoryEntry::type, Collectors.counting()));

        assertThat(counts)
                .containsOnly(
                        Map.entry(MetricType.COUNTER, 144L),
                        Map.entry(MetricType.GAUGE, 96L),
                        Map.entry(MetricType.HISTOGRAM, 7L),
                        Map.entry(MetricType.TIMER, 2L));
    }

    @Test
    void scopesFollowTheManualWithEighteenInferred() {
        assertThat(inventory.entries())
                .filteredOn(InventoryEntry::scopeInferred)
                .hasSize(18);
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.scope() == Scope.DATABASE)
                .extracting(InventoryEntry::section)
                .doesNotContain("Database page cache metrics", "Database operation count metrics");
        assertThat(inventory.entries())
                .filteredOn(entry ->
                        entry.section().startsWith("JVM") || entry.section().equals("GC metrics"))
                .allSatisfy(entry -> assertThat(entry.scope()).isEqualTo(Scope.GLOBAL));
    }

    /**
     * The Operations Manual 5 names the total size {@code store.size.total}; the total row records that a later manual
     * renames it {@code store.size.full}, which is not emitted on this line.
     */
    @Test
    void theTotalStoreSizeRowKeepsTheManualsNameAndRecordsTheLaterName() {
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.section().equals("Database store size metrics"))
                .extracting(InventoryEntry::nameTemplate)
                .containsExactly(
                        "<prefix>.store.size.total",
                        "<prefix>.store.size.database",
                        "<prefix>.store.size.available_reserved");
        assertThat(inventory.entries())
                .filteredOn(entry -> entry.nameTemplate().equals("<prefix>.store.size.total"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.notes()).contains("store.size.full", "not emitted"));
    }

    /**
     * The three count rows keep the manual's literal {@code neo4j.count} segment, so their full
     * name is {@code <prefix>.database.<db>.neo4j.count.<entity>}; the manual's reference table established it and
     * the notes record the decision. They and the deprecated {@code ids_in_use} rows are read by the entity count
     * collector, all gauges.
     */
    @Test
    void theCountRowsKeepTheLiteralNeo4jCountSegmentEstablishedByTheManual() {
        List<InventoryEntry> counts = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Database data count metrics"))
                .toList();

        assertThat(counts).hasSize(3).allSatisfy(entry -> {
            assertThat(entry.fullNameTemplate()).startsWith("<prefix>.database.<db>.neo4j.count.");
            assertThat(entry.type()).isEqualTo(MetricType.GAUGE);
            assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.DOCUMENTED);
            assertThat(entry.notes()).contains("NAME SETTLED").contains("EntityCountCollector");
        });
        assertThat(inventory.emitted())
                .filteredOn(entry -> entry.section().equals("Database data metrics"))
                .hasSize(4)
                .allSatisfy(entry -> {
                    assertThat(entry.deprecated()).as(entry.nameTemplate()).isTrue();
                    assertThat(entry.type()).isEqualTo(MetricType.GAUGE);
                    assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
                    assertThat(entry.notes()).contains("EntityCountCollector");
                });
    }

    /** The two store size rows cypher-shell reads are established by Community source; the third by the manual alone. */
    @Test
    void theStoreSizeRowsAreDatabaseGaugesInBytesNamingTheCollector() {
        List<InventoryEntry> sizes = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Database store size metrics"))
                .toList();

        assertThat(sizes).hasSize(3).allSatisfy(entry -> {
            assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
            assertThat(entry.type()).isEqualTo(MetricType.GAUGE);
            assertThat(entry.unit()).isEqualTo("bytes");
            assertThat(entry.notes()).contains("StoreSizeCollector");
        });
        assertThat(sizes)
                .filteredOn(entry -> entry.formatProvenance() == FormatProvenance.COMMUNITY_SOURCE)
                .extracting(InventoryEntry::nameTemplate)
                .containsExactlyInAnyOrder("<prefix>.store.size.total", "<prefix>.store.size.database");
        assertThat(sizes)
                .filteredOn(entry -> entry.nameTemplate().equals("<prefix>.store.size.available_reserved"))
                .extracting(InventoryEntry::formatProvenance)
                .containsExactly(FormatProvenance.DOCUMENTED);
    }

    /**
     * The 37 Cypher rows keep the manual's names: database metrics named after the collector,
     * and every cache row records that the default filter's {@code *cypher.cache*} does not match it and, for the
     * counters, the Prometheus family's doubled {@code _total}.
     */
    @Test
    void theCypherRowsAreDatabaseMetricsKeepingTheDocumentedNamesNamingTheCollector() {
        List<InventoryEntry> cypher = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Cypher metrics"))
                .toList();

        assertThat(cypher).hasSize(37).allSatisfy(entry -> {
            assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
            assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.DOCUMENTED);
            assertThat(entry.notes()).contains("CypherCollector");
        });
        assertThat(cypher)
                .filteredOn(entry -> entry.nameTemplate().startsWith("<prefix>.cypher_cache_"))
                .hasSize(35)
                .allSatisfy(entry -> {
                    assertThat(entry.fullNameTemplate()).startsWith("<prefix>.database.<db>.cypher_cache_");
                    assertThat(entry.notes()).contains("NAME SETTLED").contains("*cypher.cache* matches none");
                    if (entry.type() == MetricType.COUNTER) {
                        assertThat(entry.notes()).contains("_total_total");
                    }
                });
        assertThat(cypher)
                .filteredOn(entry -> entry.type() == MetricType.GAUGE)
                .extracting(InventoryEntry::nameTemplate)
                .containsExactlyInAnyOrder(
                        "<prefix>.cypher_cache_ast_entries",
                        "<prefix>.cypher_cache_executable_query_entries",
                        "<prefix>.cypher_cache_execution_plan_entries",
                        "<prefix>.cypher_cache_logical_plan_entries",
                        "<prefix>.cypher_cache_preparser_entries");
    }

    /**
     * The twelve index rows are database counters named as the manual names them, read by the index collector from
     * Neo4j's per-type counters; each row's notes name the collector, the Neo4j index type it reads and the test.
     */
    @Test
    void theIndexRowsAreDatabaseCountersNamingTheCollectorAndTheirIndexType() {
        List<InventoryEntry> indexes = inventory.emitted().stream()
                .filter(entry -> entry.section().equals("Database index metrics"))
                .toList();

        assertThat(indexes).hasSize(12).allSatisfy(entry -> {
            assertThat(entry.fullNameTemplate()).startsWith("<prefix>.database.<db>.index.");
            assertThat(entry.scope()).isEqualTo(Scope.DATABASE);
            assertThat(entry.type()).isEqualTo(MetricType.COUNTER);
            assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.DOCUMENTED);
            String type = entry.nameTemplate().split("\\.")[2];
            assertThat(entry.notes())
                    .contains("IndexCollector")
                    .contains("IndexType." + type.toUpperCase(Locale.ROOT))
                    .contains("IndexMetricsIT");
        });
    }

    private record Section(String anchor, int count) {}

    private static Map<String, Section> sections(Object... sectionAnchorCount) {
        Map<String, Section> sections = new LinkedHashMap<>();
        for (int i = 0; i < sectionAnchorCount.length; i += 3) {
            sections.put((String) sectionAnchorCount[i], new Section((String) sectionAnchorCount[i + 1], (Integer)
                    sectionAnchorCount[i + 2]));
        }
        return sections;
    }
}
