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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The machine-readable metric inventory: the single source of truth for which documented metrics DozerDB emits,
 * read by the conformance test and by the documentation generator. It is the tab-separated resource
 * {@value #RESOURCE} with a header line; columns are looked up by name, so a column can be added without touching
 * this reader. The format, its columns and vocabularies are documented in the resource's header comment; the reader rejects an inventory that breaks them, so a consumer never sees one.
 */
public final class MetricInventory {

    public static final String RESOURCE = "/org/dozerdb/metrics/inventory/metrics-inventory.tsv";

    private final List<InventoryEntry> entries;

    private MetricInventory(List<InventoryEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static MetricInventory load() {
        try (InputStream in = MetricInventory.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing inventory resource " + RESOURCE);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static MetricInventory parse(BufferedReader reader) throws IOException {
        List<InventoryEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> columns = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (columns == null) {
                columns = IntStream.range(0, cells.length).boxed().collect(Collectors.toMap(i -> cells[i], i -> i));
                continue;
            }
            InventoryEntry entry = entry(new Row(columns, cells));
            if (!names.add(entry.nameTemplate())) {
                throw new IllegalArgumentException(
                        "Inventory row " + entry.nameTemplate() + ": the name template appears twice");
            }
            entries.add(entry);
        }
        return new MetricInventory(entries);
    }

    private static InventoryEntry entry(Row row) {
        return new InventoryEntry(
                row.required("section"),
                row.required("name_template"),
                row.vocabulary("type", InventoryEntry.MetricType.values(), InventoryEntry.MetricType::label),
                row.required("unit"),
                row.vocabulary(
                        "scope",
                        InventoryEntry.Scope.values(),
                        scope -> scope.name().toLowerCase()),
                row.flag("scope_inferred"),
                row.required("lifecycle"),
                row.vocabulary("tier", InventoryEntry.Tier.values(), InventoryEntry.Tier::label),
                row.optional("reason"),
                row.vocabulary(
                        "format_provenance",
                        InventoryEntry.FormatProvenance.values(),
                        InventoryEntry.FormatProvenance::label),
                row.required("docs_anchor"),
                row.flag("deprecated"),
                row.vocabulary(
                        "type_rule",
                        InventoryEntry.TypeRule.values(),
                        rule -> rule.name().toLowerCase()),
                row.optional("notes"));
    }

    /** Every row, in the order of the resource (the reference page's order). */
    public List<InventoryEntry> entries() {
        return entries;
    }

    /** The rows DozerDB emits: Compatible and Partial. */
    public List<InventoryEntry> emitted() {
        return entries.stream().filter(InventoryEntry::emitted).toList();
    }

    /** The distinct sections in page order. */
    public List<String> sections() {
        return List.copyOf(
                entries.stream().map(InventoryEntry::section).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private record Row(Map<String, Integer> columns, String[] cells) {

        String optional(String column) {
            Integer index = columns.get(column);
            if (index == null) {
                throw new IllegalArgumentException(
                        "Inventory has no column '" + column + "'; columns are " + columns.keySet());
            }
            if (index >= cells.length) {
                throw new IllegalArgumentException("Inventory row has too few cells: " + Arrays.toString(cells));
            }
            return cells[index];
        }

        String required(String column) {
            String value = optional(column);
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "Inventory row " + name() + ": column '" + column + "' must not be blank");
            }
            return value;
        }

        boolean flag(String column) {
            String value = required(column);
            return switch (value) {
                case "true" -> true;
                case "false" -> false;
                default ->
                    throw new IllegalArgumentException("Inventory row " + name() + ": column '" + column + "' is '"
                            + value + "', not true or false");
            };
        }

        <E extends Enum<E>> E vocabulary(String column, E[] values, java.util.function.Function<E, String> label) {
            String value = required(column);
            for (E candidate : values) {
                if (label.apply(candidate).equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Inventory row " + name() + ": column '" + column + "' is '" + value
                    + "', not one of " + Arrays.stream(values).map(label).toList());
        }

        private String name() {
            Integer index = columns.get("name_template");
            return index == null || index >= cells.length ? Arrays.toString(cells) : cells[index];
        }
    }
}
