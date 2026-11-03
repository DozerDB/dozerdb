/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import static org.assertj.core.api.Assertions.assertThat;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingWindowReservoir;
import com.codahale.metrics.Timer;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.dropwizard.DropwizardExports;
import io.prometheus.client.exporter.common.TextFormat;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one test that drives a runtime component of the module directly instead of through a started server: DozerDB's
 * exposition over a Dropwizard registry must produce what the Prometheus Dropwizard bridge
 * ({@code simpleclient_dropwizard} 0.16.0, test scope) produces over the same registry, for every metric type. The
 * bridge needs a registry to read, so the comparison cannot be made from outside; {@code PrometheusEndpointIT} checks
 * the scrape of a running server.
 *
 * <p>The reference encodes Neo4j's one deviation from the stock bridge, established from public dashboards and
 * published scrapes of Neo4j's endpoint: a documented counter, a Dropwizard {@link Counter} in the registry, is exposed
 * as a Prometheus {@code counter} family with the {@code _total} suffix, which the stock bridge produces only for a
 * {@link Meter}. The reference is therefore the stock bridge over a shadow registry in which every {@code Counter} is
 * mirrored by a {@code Meter} marked to the same count, with the {@code type=} class name inside that family's
 * {@code # HELP} line put back to {@code Counter}'s. Everything else is compared byte for byte. The bridge's family
 * order is hash order, so the reference writes its families sorted by name, as DozerDB does.
 */
class PrometheusExpositionTest {

    @Test
    void aLongGaugeIsExposedAsTheBridgeExposesIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.vm.heap.used", (Gauge<Long>) () -> 7864451L);

        assertThat(new PrometheusExposition(registry).text()).isEqualTo(bridge(registry));
    }

    @Test
    void aGaugeIsAGaugeFamilyWithOneSuffixLessSampleAndNoLabels() {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.vm.heap.used", (Gauge<Long>) () -> 7864451L);

        assertThat(new PrometheusExposition(registry).text())
                .isEqualTo(
                        """
                        # HELP neo4j_dbms_vm_heap_used Generated from Dropwizard metric import (metric=neo4j.dbms.vm.heap.used, type=%s)
                        # TYPE neo4j_dbms_vm_heap_used gauge
                        neo4j_dbms_vm_heap_used 7864451.0
                        """
                                .formatted(registry.getGauges()
                                        .get("neo4j.dbms.vm.heap.used")
                                        .getClass()
                                        .getName()));
    }

    @Test
    void doubleAndBooleanGaugesAreExposedAsTheBridgeExposesThem() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.page_cache.hit_ratio", (Gauge<Double>) () -> 0.9876543210123);
        registry.register("neo4j.dbms.something.flag", (Gauge<Boolean>) () -> true);
        registry.register("neo4j.dbms.something.infinite", (Gauge<Double>) () -> Double.POSITIVE_INFINITY);

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text)
                .contains("neo4j_dbms_page_cache_hit_ratio 0.9876543210123\n")
                .contains("neo4j_dbms_something_flag 1.0\n")
                .contains("neo4j_dbms_something_infinite +Inf\n");
    }

    @Test
    void aGaugeWithoutANumericValueIsSkippedAsTheBridgeSkipsIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.disabled.guarded", (Gauge<Long>) () -> null);
        registry.register("neo4j.dbms.textual", (Gauge<String>) () -> "not a number");
        registry.register("neo4j.dbms.vm.heap.used", (Gauge<Long>) () -> 1L);

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text).doesNotContain("disabled_guarded").doesNotContain("textual");
        assertThat(text).contains("neo4j_dbms_vm_heap_used 1.0");
    }

    @Test
    void aCounterIsACounterFamilyWithTheTotalSuffixAsNeo4jExposesIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.counter("neo4j.database.neo4j.transaction.committed").inc(3);

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text)
                .as("the shape of every counter in published scrapes of Neo4j 4.4.0 and 5.11.0")
                .isEqualTo(
                        """
                        # HELP neo4j_database_neo4j_transaction_committed_total Generated from Dropwizard metric import (metric=neo4j.database.neo4j.transaction.committed, type=com.codahale.metrics.Counter)
                        # TYPE neo4j_database_neo4j_transaction_committed_total counter
                        neo4j_database_neo4j_transaction_committed_total 3.0
                        """);
    }

    @Test
    void aHistogramIsASummaryWithSixQuantilesAndACountAndNoSum() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        Histogram histogram = registry.histogram("neo4j.database.neo4j.db.query.execution.latency.millis");
        for (long millis : new long[] {1, 2, 3, 5, 8, 13, 21}) {
            histogram.update(millis);
        }

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text)
                .as("the shape of the histograms in published scrapes of Neo4j 4.4.0 and 5.11.0")
                .contains("# TYPE neo4j_database_neo4j_db_query_execution_latency_millis summary\n")
                .contains("neo4j_database_neo4j_db_query_execution_latency_millis{quantile=\"0.5\",} ")
                .contains("{quantile=\"0.75\",} ")
                .contains("{quantile=\"0.95\",} ")
                .contains("{quantile=\"0.98\",} ")
                .contains("{quantile=\"0.99\",} ")
                .contains("{quantile=\"0.999\",} ")
                .contains("neo4j_database_neo4j_db_query_execution_latency_millis_count 7.0\n")
                .doesNotContain("_sum");
    }

    @Test
    void aTimerIsASummaryWithValuesInSecondsAsTheBridgeExposesIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        Timer timer = registry.register(
                "neo4j.database.neo4j.cluster.raft.message_processing_timer",
                new Timer(new SlidingWindowReservoir(16)));
        timer.update(Duration.ofMillis(1500));

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text)
                .contains("# TYPE neo4j_database_neo4j_cluster_raft_message_processing_timer summary\n")
                .contains("neo4j_database_neo4j_cluster_raft_message_processing_timer{quantile=\"0.5\",} 1.5\n")
                .contains("neo4j_database_neo4j_cluster_raft_message_processing_timer{quantile=\"0.999\",} 1.5\n")
                .contains("neo4j_database_neo4j_cluster_raft_message_processing_timer_count 1.0\n");
    }

    @Test
    void aMeterIsACounterFamilyWithTheTotalSuffixAsTheBridgeExposesIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.meter("neo4j.database.neo4j.db.query.execution.success").mark(4);

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text)
                .contains("# TYPE neo4j_database_neo4j_db_query_execution_success_total counter\n")
                .contains("neo4j_database_neo4j_db_query_execution_success_total 4.0\n");
    }

    @Test
    void aRegistryHoldingEveryMetricTypeIsExposedAsTheBridgeExposesIt() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.vm.heap.used", (Gauge<Long>) () -> 7864451L);
        registry.register("neo4j.dbms.page_cache.hit_ratio", (Gauge<Double>) () -> 0.75);
        registry.register("neo4j.dbms.vm.memory.pool.g1 eden-space", (Gauge<Long>) () -> 42L);
        registry.counter("neo4j.database.neo4j.transaction.committed").inc(3);
        registry.counter("Neo4j_1.database.my-db.check_point.total_time").inc(9);
        registry.histogram("neo4j.database.neo4j.db.query.execution.latency.millis")
                .update(12);
        registry.register("neo4j.database.neo4j.cluster.raft.message_processing_timer", new Timer())
                .update(Duration.ofMillis(5));
        registry.meter("neo4j.database.neo4j.db.query.execution.success").mark();

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text.lines().filter(line -> line.startsWith("# TYPE ")).toList())
                .as("one family per metric, in name order, each with its type")
                .containsExactly(
                        "# TYPE Neo4j_1_database_my_db_check_point_total_time_total counter",
                        "# TYPE neo4j_database_neo4j_cluster_raft_message_processing_timer summary",
                        "# TYPE neo4j_database_neo4j_db_query_execution_latency_millis summary",
                        "# TYPE neo4j_database_neo4j_db_query_execution_success_total counter",
                        "# TYPE neo4j_database_neo4j_transaction_committed_total counter",
                        "# TYPE neo4j_dbms_page_cache_hit_ratio gauge",
                        "# TYPE neo4j_dbms_vm_heap_used gauge",
                        "# TYPE neo4j_dbms_vm_memory_pool_g1_eden_space gauge");
    }

    @Test
    void namesAreSanitisedAsTheBridgeSanitisesThem() {
        assertThat(PrometheusExposition.sanitize("neo4j.dbms.vm.gc.time.g1-young generation"))
                .isEqualTo("neo4j_dbms_vm_gc_time_g1_young_generation");
        assertThat(PrometheusExposition.sanitize("Neo4j_1.database.my-db.transaction.started"))
                .isEqualTo("Neo4j_1_database_my_db_transaction_started");
        assertThat(PrometheusExposition.sanitize("1st.metric")).isEqualTo("_st_metric");
        assertThat(PrometheusExposition.sanitize("ns:metric_9")).isEqualTo("ns:metric_9");
    }

    @Test
    void namesThatSanitiseAlikeMergeIntoTheFirstFamilyAsTheBridgeMergesThem() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.register("neo4j.dbms.a.b", (Gauge<Long>) () -> 1L);
        registry.counter("neo4j.dbms.a-b").inc(2);

        String text = new PrometheusExposition(registry).text();

        assertThat(text).isEqualTo(bridge(registry));
        assertThat(text.lines().filter(line -> line.startsWith("# TYPE ")).toList())
                .containsExactly("# TYPE neo4j_dbms_a_b gauge");
        assertThat(text).contains("\nneo4j_dbms_a_b 1.0\nneo4j_dbms_a_b_total 2.0\n");
    }

    /** Why the reference mirrors counters as meters: the stock bridge maps a Dropwizard counter to a gauge. */
    @Test
    void theStockBridgeExposesACounterAsASuffixLessGauge() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        registry.counter("neo4j.database.neo4j.transaction.committed").inc(3);
        StringWriter out = new StringWriter();

        TextFormat.write004(out, Collections.enumeration(new DropwizardExports(registry).collect()));

        assertThat(out.toString())
                .contains("# TYPE neo4j_database_neo4j_transaction_committed gauge\n")
                .contains("\nneo4j_database_neo4j_transaction_committed 3.0\n")
                .doesNotContain("_total");
    }

    /**
     * The stock bridge over the shadow registry (counters mirrored by meters), families sorted by name, and the
     * mirrored families' {@code # HELP} class name put back to {@code Counter}'s.
     */
    static String bridge(MetricRegistry registry) throws IOException {
        MetricRegistry shadow = new MetricRegistry();
        List<String> mirrored = new ArrayList<>();
        registry.getMetrics().forEach((name, metric) -> {
            if (metric instanceof Counter counter) {
                Meter meter = new Meter();
                meter.mark(counter.getCount());
                shadow.register(name, meter);
                mirrored.add(name);
            } else {
                shadow.register(name, metric);
            }
        });
        List<MetricFamilySamples> families = new ArrayList<>(new DropwizardExports(shadow).collect());
        families.sort(Comparator.comparing(family -> family.name));
        StringWriter out = new StringWriter();
        TextFormat.write004(out, Collections.enumeration(families));
        String text = out.toString();
        for (String name : mirrored) {
            text = text.replace(
                    "(metric=" + name + ", type=" + Meter.class.getName() + ")",
                    "(metric=" + name + ", type=" + Counter.class.getName() + ")");
        }
        return text;
    }
}
