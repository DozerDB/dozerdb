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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.dozerdb.metrics.inventory.InventoryEntry.FormatProvenance;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;
import org.dozerdb.metrics.inventory.InventoryEntry.Scope;
import org.dozerdb.metrics.inventory.InventoryEntry.Tier;
import org.dozerdb.metrics.inventory.InventoryEntry.TypeRule;
import org.junit.jupiter.api.Test;

/**
 * The rules of the inventory format, enforced by the reader so that every consumer (the conformance test, the
 * documentation generator) works from a valid inventory: columns looked up by name, one row per documented
 * metric, controlled vocabularies, a reason on every non-Compatible row and an explicit format provenance on
 * every row.
 */
class InventoryFormatTest {

    private static final String HEADER = "section\tname_template\ttype\tunit\tscope\tscope_inferred\tlifecycle\ttier"
            + "\treason\tformat_provenance\tdocs_anchor\tdeprecated\ttype_rule\tnotes";
    private static final String ANCHOR =
            "https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-heap-metrics";

    @Test
    void aRowIsReadByColumnName() throws IOException {
        InventoryEntry entry = single(row(
                "JVM Heap metrics",
                "<prefix>.vm.heap.used",
                "gauge",
                "bytes",
                "global",
                "false",
                "-",
                "Compatible",
                "",
                "community-source",
                ANCHOR,
                "false",
                "documented",
                "read from the MemoryMXBean"));

        assertThat(entry.section()).isEqualTo("JVM Heap metrics");
        assertThat(entry.nameTemplate()).isEqualTo("<prefix>.vm.heap.used");
        assertThat(entry.type()).isEqualTo(MetricType.GAUGE);
        assertThat(entry.unit()).isEqualTo("bytes");
        assertThat(entry.scope()).isEqualTo(Scope.GLOBAL);
        assertThat(entry.scopeInferred()).isFalse();
        assertThat(entry.lifecycle()).isEqualTo("-");
        assertThat(entry.tier()).isEqualTo(Tier.COMPATIBLE);
        assertThat(entry.reason()).isEmpty();
        assertThat(entry.formatProvenance()).isEqualTo(FormatProvenance.COMMUNITY_SOURCE);
        assertThat(entry.docsAnchor()).isEqualTo(ANCHOR);
        assertThat(entry.deprecated()).isFalse();
        assertThat(entry.typeRule()).isEqualTo(TypeRule.DOCUMENTED);
        assertThat(entry.notes()).isEqualTo("read from the MemoryMXBean");
    }

    @Test
    void theFullNameTemplateAddsTheScopeSegmentTheManualLeavesOut() throws IOException {
        assertThat(global("<prefix>.vm.heap.used").fullNameTemplate()).isEqualTo("<prefix>.dbms.vm.heap.used");
        assertThat(database("<prefix>.transaction.started").fullNameTemplate())
                .isEqualTo("<prefix>.database.<db>.transaction.started");
    }

    @Test
    void theFullNameTemplateKeepsAnExplicitScopeSegment() throws IOException {
        assertThat(global("<prefix>.dbms.bolt.connections_opened").fullNameTemplate())
                .isEqualTo("<prefix>.dbms.bolt.connections_opened");
        assertThat(database("<prefix>.database.<db>.pool.<pool>.<db>.used_heap").fullNameTemplate())
                .isEqualTo("<prefix>.database.<db>.pool.<pool>.<db>.used_heap");
    }

    @Test
    void theMetricNameIsTheTemplateWithoutPrefixAndScope() throws IOException {
        assertThat(global("<prefix>.dbms.bolt.connections_opened").metricName()).isEqualTo("bolt.connections_opened");
        assertThat(database("<prefix>.database.<db>.pool.<pool>.<db>.free").metricName())
                .isEqualTo("pool.<pool>.<db>.free");
        assertThat(database("<prefix>.neo4j.count.node").metricName()).isEqualTo("neo4j.count.node");
    }

    @Test
    void instancePlaceholdersAreThoseTheRunningJvmResolves() throws IOException {
        assertThat(global("<prefix>.vm.gc.time.<gc>").hasInstancePlaceholders()).isTrue();
        assertThat(database("<prefix>.database.<db>.pool.<pool>.<db>.free").hasInstancePlaceholders())
                .isTrue();
        assertThat(database("<prefix>.transaction.started").hasInstancePlaceholders())
                .isFalse();
    }

    @Test
    void anExplicitScopeSegmentMustAgreeWithTheScopeColumn() {
        assertThatThrownBy(() -> database("<prefix>.dbms.bolt.connections_opened"))
                .hasMessageContaining("<prefix>.dbms.bolt.connections_opened")
                .hasMessageContaining("scope");
    }

    @Test
    void nameTemplatesMustBeUnique() {
        assertThatThrownBy(() -> parse(
                        HEADER + "\n" + heapRow("<prefix>.vm.heap.used") + "\n" + heapRow("<prefix>.vm.heap.used")))
                .hasMessageContaining("<prefix>.vm.heap.used")
                .hasMessageContaining("twice");
    }

    @Test
    void onlyKnownPlaceholdersAreAllowed() {
        assertThatThrownBy(() -> global("<prefix>.vm.gc.time.<collector>")).hasMessageContaining("<collector>");
    }

    @Test
    void aNonCompatibleRowNeedsAReasonAndACompatibleRowHasNone() {
        assertThatThrownBy(() -> single(row(
                        "GC metrics",
                        "<prefix>.vm.gc.count.<gc>",
                        "counter",
                        "-",
                        "global",
                        "false",
                        "-",
                        "Unsupported",
                        "",
                        "pending",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("<prefix>.vm.gc.count.<gc>")
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "but",
                        "community-source",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("reason");
    }

    @Test
    void formatProvenanceIsPendingUntilEmittedAndNeverEmittedWhenNotApplicable() {
        assertThatThrownBy(() -> single(row(
                        "GC metrics",
                        "<prefix>.vm.gc.count.<gc>",
                        "counter",
                        "-",
                        "global",
                        "false",
                        "-",
                        "Unsupported",
                        "not yet",
                        "documented",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("pending");
        assertThatThrownBy(() -> single(row(
                        "Raft metrics",
                        "<prefix>.cluster.raft.term",
                        "gauge",
                        "-",
                        "database",
                        "false",
                        "-",
                        "Not applicable",
                        "no cluster",
                        "pending",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("never-emitted");
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "",
                        "pending",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("pending");
    }

    @Test
    void vocabulariesAreControlled() {
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "meter",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "",
                        "community-source",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("meter");
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Supported",
                        "",
                        "community-source",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("Supported");
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "",
                        "community-source",
                        ANCHOR,
                        "false",
                        "sysinfo",
                        "")))
                .hasMessageContaining("sysinfo");
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "bytes",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "",
                        "community-source",
                        ANCHOR,
                        "maybe",
                        "documented",
                        "")))
                .hasMessageContaining("maybe");
    }

    @Test
    void everyColumnButReasonAndNotesIsRequired() {
        assertThatThrownBy(() -> single(row(
                        "JVM Heap metrics",
                        "<prefix>.vm.heap.used",
                        "gauge",
                        "",
                        "global",
                        "false",
                        "-",
                        "Compatible",
                        "",
                        "community-source",
                        ANCHOR,
                        "false",
                        "documented",
                        "")))
                .hasMessageContaining("unit");
        assertThatThrownBy(() -> parse("name_template\ttype\n<prefix>.vm.heap.used\tgauge"))
                .hasMessageContaining("section");
    }

    @Test
    void commentsAndBlankLinesAreSkipped() throws IOException {
        MetricInventory inventory =
                parse("# a comment\n\n" + HEADER + "\n# another\n" + heapRow("<prefix>.vm.heap.used") + "\n\n");

        assertThat(inventory.entries()).hasSize(1);
    }

    private static InventoryEntry global(String nameTemplate) throws IOException {
        return single(row(
                "JVM Heap metrics",
                nameTemplate,
                "gauge",
                "bytes",
                "global",
                "false",
                "-",
                "Unsupported",
                "not yet",
                "pending",
                ANCHOR,
                "false",
                "documented",
                ""));
    }

    private static InventoryEntry database(String nameTemplate) throws IOException {
        return single(row(
                "Database transaction metrics",
                nameTemplate,
                "counter",
                "-",
                "database",
                "false",
                "-",
                "Unsupported",
                "not yet",
                "pending",
                ANCHOR,
                "false",
                "documented",
                ""));
    }

    private static String heapRow(String nameTemplate) {
        return row(
                "JVM Heap metrics",
                nameTemplate,
                "gauge",
                "bytes",
                "global",
                "false",
                "-",
                "Compatible",
                "",
                "community-source",
                ANCHOR,
                "false",
                "documented",
                "");
    }

    private static InventoryEntry single(String row) throws IOException {
        return parse(HEADER + "\n" + row).entries().get(0);
    }

    private static MetricInventory parse(String tsv) throws IOException {
        return MetricInventory.parse(new BufferedReader(new StringReader(tsv)));
    }

    private static String row(String... cells) {
        return String.join("\t", cells);
    }
}
