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

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dozerdb.metrics.inventory.InventoryEntry.FormatProvenance;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;
import org.dozerdb.metrics.inventory.InventoryEntry.Scope;
import org.dozerdb.metrics.inventory.InventoryEntry.Tier;
import org.dozerdb.metrics.inventory.InventoryEntry.TypeRule;
import org.junit.jupiter.api.Test;

/**
 * The comparison behind {@link InventoryConformanceIT}: the inventory's emitted rows, expanded against a server
 * (its prefix, its databases, any number of JVM instances), against the MBeans observed in the JMX domain. It
 * must report an extra registered name, a missing Compatible or Partial name and a type that disagrees with the
 * inventory, and nothing when the two agree.
 */
class InventoryConformanceTest {

    private static final Set<String> GAUGE = Set.of("Value", "Number");
    private static final Set<String> COUNTER = Set.of("Count");
    private static final Set<String> HISTOGRAM = Set.of(
            "Count",
            "Min",
            "Max",
            "Mean",
            "StdDev",
            "50thPercentile",
            "75thPercentile",
            "95thPercentile",
            "98thPercentile",
            "99thPercentile",
            "999thPercentile",
            "SnapshotSize");
    private static final Set<String> TIMER = Set.of(
            "Count",
            "Min",
            "Max",
            "Mean",
            "StdDev",
            "50thPercentile",
            "75thPercentile",
            "95thPercentile",
            "98thPercentile",
            "99thPercentile",
            "999thPercentile",
            "SnapshotSize",
            "MeanRate",
            "OneMinuteRate",
            "FiveMinuteRate",
            "FifteenMinuteRate",
            "RateUnit",
            "DurationUnit");

    @Test
    void aGlobalRowExpectsOneNameAndADatabaseRowOnePerDatabase() {
        List<ExpectedMetric> expected = ExpectedMetric.expand(
                List.of(
                        emitted("<prefix>.vm.heap.used", MetricType.GAUGE, Scope.GLOBAL),
                        emitted("<prefix>.transaction.started", MetricType.COUNTER, Scope.DATABASE)),
                "dozer",
                List.of("neo4j", "system"));

        assertThat(expected)
                .extracting(ExpectedMetric::description)
                .containsExactly(
                        "dozer.dbms.vm.heap.used",
                        "dozer.database.neo4j.transaction.started",
                        "dozer.database.system.transaction.started");
    }

    @Test
    void anInstancePlaceholderMatchesAnyInstanceNameAndTheDatabaseNameIsLiteral() {
        ExpectedMetric pool = ExpectedMetric.expand(
                        List.of(emitted(
                                "<prefix>.database.<db>.pool.<pool>.<db>.free", MetricType.GAUGE, Scope.DATABASE)),
                        "neo4j",
                        List.of("my.db"))
                .get(0);

        assertThat(pool.matches("neo4j.database.my.db.pool.transaction.my.db.free"))
                .isTrue();
        assertThat(pool.matches("neo4j.database.my.db.pool.other.my.db.free")).isTrue();
        assertThat(pool.matches("neo4j.database.myxdb.pool.transaction.myxdb.free"))
                .isFalse();
        assertThat(pool.matches("neo4j.database.my.db.pool.a.b.my.db.free")).isFalse();
    }

    @Test
    void agreementReportsNothing() {
        InventoryConformance.Report report = compare(
                List.of(
                        emitted("<prefix>.vm.heap.used", MetricType.GAUGE, Scope.GLOBAL),
                        emitted("<prefix>.vm.gc.count.<gc>", MetricType.COUNTER, Scope.GLOBAL)),
                Map.of(
                        "neo4j.dbms.vm.heap.used", GAUGE,
                        "neo4j.dbms.vm.gc.count.g1_young_generation", COUNTER,
                        "neo4j.dbms.vm.gc.count.g1_old_generation", COUNTER));

        assertThat(report.problems()).isEmpty();
    }

    @Test
    void anObservedNameNoEmittedRowProducesIsExtra() {
        InventoryConformance.Report report = compare(
                List.of(emitted("<prefix>.vm.heap.used", MetricType.GAUGE, Scope.GLOBAL)),
                Map.of("neo4j.dbms.vm.heap.used", GAUGE, "neo4j.dbms.vm.heap.extra", GAUGE));

        assertThat(report.extra()).containsExactly("neo4j.dbms.vm.heap.extra");
        assertThat(report.problems()).hasSize(1).first().asString().contains("neo4j.dbms.vm.heap.extra");
    }

    @Test
    void anEmittedRowNoObservedNameMatchesIsMissing() {
        InventoryConformance.Report report = compare(
                List.of(
                        emitted("<prefix>.vm.heap.used", MetricType.GAUGE, Scope.GLOBAL),
                        emitted("<prefix>.vm.gc.count.<gc>", MetricType.COUNTER, Scope.GLOBAL),
                        emitted("<prefix>.transaction.started", MetricType.COUNTER, Scope.DATABASE)),
                Map.of("neo4j.dbms.vm.heap.used", GAUGE));

        assertThat(report.missing())
                .containsExactly("neo4j.dbms.vm.gc.count.<gc>", "neo4j.database.neo4j.transaction.started");
    }

    @Test
    void anObservedShapeThatDisagreesWithTheInventoryTypeIsAMismatch() {
        InventoryConformance.Report report = compare(
                List.of(
                        emitted("<prefix>.vm.heap.used", MetricType.GAUGE, Scope.GLOBAL),
                        emitted("<prefix>.transaction.started", MetricType.COUNTER, Scope.DATABASE)),
                Map.of("neo4j.dbms.vm.heap.used", COUNTER, "neo4j.database.neo4j.transaction.started", COUNTER));

        assertThat(report.typeMismatches())
                .containsExactly(
                        "neo4j.dbms.vm.heap.used: inventory says gauge, MBean attributes [Count] are a counter");
    }

    @Test
    void mbeanAttributeShapesIdentifyTheInventoryTypes() {
        assertThat(JmxMetricTypes.fromAttributes(GAUGE)).contains(MetricType.GAUGE);
        assertThat(JmxMetricTypes.fromAttributes(COUNTER)).contains(MetricType.COUNTER);
        assertThat(JmxMetricTypes.fromAttributes(HISTOGRAM)).contains(MetricType.HISTOGRAM);
        assertThat(JmxMetricTypes.fromAttributes(TIMER)).contains(MetricType.TIMER);
        assertThat(JmxMetricTypes.fromAttributes(Set.of("Count", "MeanRate", "OneMinuteRate")))
                .as("a meter is no inventory type")
                .isEmpty();
        assertThat(JmxMetricTypes.fromAttributes(Set.of("Value", "Count"))).isEmpty();
    }

    @Test
    void anObservedNameMatchingSeveralRowsIsAProblemOfTheInventory() {
        InventoryConformance.Report report = compare(
                List.of(
                        emitted("<prefix>.vm.memory.pool.<pool>", MetricType.GAUGE, Scope.GLOBAL),
                        emitted("<prefix>.vm.memory.pool.g1_eden_space", MetricType.GAUGE, Scope.GLOBAL)),
                Map.of("neo4j.dbms.vm.memory.pool.g1_eden_space", GAUGE));

        assertThat(report.problems()).hasSize(1).first().asString().contains("several");
    }

    private static InventoryConformance.Report compare(
            List<InventoryEntry> emitted, Map<String, Set<String>> observed) {
        return InventoryConformance.compare(ExpectedMetric.expand(emitted, "neo4j", List.of("neo4j")), observed);
    }

    private static InventoryEntry emitted(String nameTemplate, MetricType type, Scope scope) {
        return new InventoryEntry(
                "a section",
                nameTemplate,
                type,
                "-",
                scope,
                false,
                "-",
                Tier.COMPATIBLE,
                "",
                FormatProvenance.COMMUNITY_SOURCE,
                "https://neo4j.com/docs/operations-manual/2026.10/monitoring/metrics/reference/#a-section",
                false,
                TypeRule.DOCUMENTED,
                "");
    }
}
