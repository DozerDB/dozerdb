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

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dozerdb.metrics.registry.MetricNames;

/**
 * One documented metric and its compatibility classification: a row of the inventory.
 *
 * <p>The name template is spelled as the manual spells it. The manual writes the scope segment ({@code dbms.} or
 * {@code database.<db>.}) into some tables and leaves it out of others; {@link #fullNameTemplate()} always carries
 * it, and {@link #metricName()} never does. Placeholders: {@code <prefix>} (the configured metric prefix),
 * {@code <db>} (a database name) and the instance placeholders {@code <pool>}, {@code <gc>} and
 * {@code <bufferpool>}, which the running JVM resolves to one name per memory pool, garbage collector or buffer
 * pool.
 *
 * @param section the reference page's section (table caption), the grouping of the public matrix
 * @param nameTemplate the name as the manual writes it, starting with {@code <prefix>.}
 * @param type the metric type the manual documents
 * @param unit the documented unit, or {@code -} when the manual states none
 * @param scope global or database
 * @param scopeInferred whether the scope is inferred rather than stated by the manual
 * @param lifecycle the manual's lifecycle label ({@code Introduced in}, {@code Deprecated in}, ...) or {@code -}
 * @param tier the compatibility tier
 * @param reason why the tier is not Compatible; empty for a Compatible metric
 * @param formatProvenance the strongest source that established the output format, in the compatibility oracle's order
 * @param docsAnchor the metric's section in the pinned Operations Manual
 * @param deprecated whether the manual deprecates the metric (DozerDB still emits deprecated metrics)
 * @param typeRule which rule of the metric type policy settled {@code type}
 * @param notes free text: naming questions, consumers that read the metric, page errata
 */
public record InventoryEntry(
        String section,
        String nameTemplate,
        MetricType type,
        String unit,
        Scope scope,
        boolean scopeInferred,
        String lifecycle,
        Tier tier,
        String reason,
        FormatProvenance formatProvenance,
        String docsAnchor,
        boolean deprecated,
        TypeRule typeRule,
        String notes) {

    public static final String PREFIX_PLACEHOLDER = "<prefix>";
    public static final String DATABASE_PLACEHOLDER = "<db>";

    /** Placeholders the running JVM resolves to one name per instance. */
    public static final Set<String> INSTANCE_PLACEHOLDERS = Set.of("<pool>", "<gc>", "<bufferpool>");

    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>");
    private static final String GLOBAL_SEGMENT = PREFIX_PLACEHOLDER + "." + MetricNames.GLOBAL_SCOPE + ".";
    private static final String DATABASE_SEGMENT =
            PREFIX_PLACEHOLDER + "." + MetricNames.DATABASE_SCOPE + "." + DATABASE_PLACEHOLDER + ".";

    public InventoryEntry {
        if (!nameTemplate.startsWith(PREFIX_PLACEHOLDER + ".")) {
            throw invalid(nameTemplate, "name template must start with " + PREFIX_PLACEHOLDER + ".");
        }
        Matcher placeholders = PLACEHOLDER.matcher(nameTemplate);
        while (placeholders.find()) {
            String placeholder = placeholders.group();
            if (!placeholder.equals(PREFIX_PLACEHOLDER)
                    && !placeholder.equals(DATABASE_PLACEHOLDER)
                    && !INSTANCE_PLACEHOLDERS.contains(placeholder)) {
                throw invalid(nameTemplate, "unknown placeholder " + placeholder);
            }
        }
        if (nameTemplate.startsWith(GLOBAL_SEGMENT) && scope != Scope.GLOBAL
                || nameTemplate.startsWith(DATABASE_SEGMENT) && scope != Scope.DATABASE) {
            throw invalid(nameTemplate, "the explicit scope segment disagrees with scope " + scope);
        }
        if (tier == Tier.COMPATIBLE && !reason.isEmpty()) {
            throw invalid(nameTemplate, "a Compatible metric has no reason; use notes");
        }
        if (tier != Tier.COMPATIBLE && reason.isBlank()) {
            throw invalid(nameTemplate, "a " + tier.label() + " metric needs a reason");
        }
        FormatProvenance.check(nameTemplate, tier, formatProvenance);
    }

    private static IllegalArgumentException invalid(String nameTemplate, String problem) {
        return new IllegalArgumentException("Inventory row " + nameTemplate + ": " + problem);
    }

    public enum Scope {
        GLOBAL,
        DATABASE
    }

    /** The metric types the manual documents, plus the Dropwizard timer its two Raft timer rows describe. */
    public enum MetricType {
        GAUGE,
        COUNTER,
        HISTOGRAM,
        TIMER;

        public String label() {
            return name().toLowerCase();
        }
    }

    public enum Tier {
        COMPATIBLE("Compatible"),
        PARTIAL("Partial"),
        UNSUPPORTED("Unsupported"),
        NOT_APPLICABLE("Not applicable");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean emitted() {
            return this == COMPATIBLE || this == PARTIAL;
        }
    }

    /**
     * The strongest source that established an emitted metric's output format, in the compatibility oracle's order (the
     * pinned manual, Neo4j's Community source, historical open source, library convention, public dashboards), plus the two explicit values of metrics that have no output format yet or ever.
     */
    public enum FormatProvenance {
        /** The pinned Operations Manual states the format. */
        DOCUMENTED("documented"),
        /** Neo4j's Community source hard-codes it (cypher-shell and Browser {@code :sysinfo}, fleet management). */
        COMMUNITY_SOURCE("community-source"),
        /** The library default, corroborated by the historical Neo4j 3.4 open source metrics module. */
        HISTORICAL_OPEN_SOURCE("historical-open-source"),
        /** The library default only (Dropwizard reporters, Prometheus Dropwizard bridge). */
        LIBRARY_CONVENTION("library-convention"),
        /** Public community dashboards only. */
        DASHBOARDS("dashboards"),
        /** An Unsupported metric: replaced once a collector emits it. */
        PENDING("pending"),
        /** A Not applicable metric: never emitted, so it has no output format. */
        NEVER_EMITTED("never-emitted");

        private final String label;

        FormatProvenance(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static void check(String nameTemplate, Tier tier, FormatProvenance provenance) {
            FormatProvenance required =
                    tier == Tier.NOT_APPLICABLE ? NEVER_EMITTED : tier == Tier.UNSUPPORTED ? PENDING : null;
            if (required != null && provenance != required) {
                throw invalid(nameTemplate, "a " + tier.label() + " metric has format provenance " + required.label);
            }
            if (required == null && (provenance == PENDING || provenance == NEVER_EMITTED)) {
                throw invalid(
                        nameTemplate, "an emitted metric records the source of its format, not " + provenance.label);
            }
        }
    }

    /** Which rule of the metric type policy settled the type: the documented type decides, the oracle breaks ties. */
    public enum TypeRule {
        /** The manual's type marker was unambiguous. */
        DOCUMENTED,
        /** The manual was ambiguous and the compatibility oracle settled it; the notes say how. */
        ORACLE
    }

    /** The name with the scope segment, for example {@code <prefix>.database.<db>.transaction.started}. */
    public String fullNameTemplate() {
        return scope == Scope.GLOBAL ? GLOBAL_SEGMENT + metricName() : DATABASE_SEGMENT + metricName();
    }

    /** The name without prefix and scope segment, for example {@code transaction.started}. */
    public String metricName() {
        for (String segment : List.of(GLOBAL_SEGMENT, DATABASE_SEGMENT, PREFIX_PLACEHOLDER + ".")) {
            if (nameTemplate.startsWith(segment)) {
                return nameTemplate.substring(segment.length());
            }
        }
        throw new IllegalStateException(nameTemplate);
    }

    /** Whether the running JVM decides how many metrics this row produces ({@code <pool>}, {@code <gc>}, {@code <bufferpool>}). */
    public boolean hasInstancePlaceholders() {
        return INSTANCE_PLACEHOLDERS.stream().anyMatch(nameTemplate::contains);
    }

    /** Whether DozerDB emits this metric: Compatible and Partial metrics are emitted, the other tiers are not. */
    public boolean emitted() {
        return tier.emitted();
    }
}
