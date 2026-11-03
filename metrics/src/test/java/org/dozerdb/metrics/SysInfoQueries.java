/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics;

import java.util.List;
import java.util.Map;

/**
 * The {@code :sysinfo} fixture: the JMX queries cypher-shell and the Browser issue, verbatim,
 * with the metrics each asks for, as read at Neo4j tag {@code 5.26.32} from
 * {@code community/cypher-shell/cypher-shell/src/main/java/org/neo4j/shell/commands/SysInfo.java} and from the
 * bundled LTS Browser's {@code browser/src/browser/modules/Stream/SysInfoFrame/sysinfoHelpers.tsx}. Both clients read
 * only ids in use, store size, page cache and transaction metrics; neither asks for a JVM, memory pool or server
 * thread metric, so {@code :sysinfo} never shows those. Both show whichever of {@code attributes.Value.value} and
 * {@code attributes.Count.value} is present and pin no type. The release-gate check ({@code scripts/metrics-check.py}) replays the same
 * lists against a packaged server.
 */
public final class SysInfoQueries {

    private SysInfoQueries() {}

    /** One metric a client asks for: its name without the scope segment, and whether it is a database metric. */
    public record Metric(String name, boolean database) {}

    /** cypher-shell {@code SysInfo.allMetrics}, in its groups and order. */
    public static final List<Metric> CYPHER_SHELL = List.of(
            new Metric("ids_in_use.property", true),
            new Metric("ids_in_use.relationship", true),
            new Metric("ids_in_use.relationship_type", true),
            new Metric("store.size.total", true),
            new Metric("store.size.database", true),
            new Metric("page_cache.hits", false),
            new Metric("page_cache.hit_ratio", false),
            new Metric("page_cache.usage_ratio", false),
            new Metric("page_cache.page_faults", false),
            new Metric("transaction.last_committed_tx_id", true),
            new Metric("transaction.active_read", true),
            new Metric("transaction.active_write", true),
            new Metric("transaction.peak_concurrent", true),
            new Metric("transaction.committed_read", true),
            new Metric("transaction.committed_write", true));

    /**
     * The bundled LTS Browser's {@code sysInfoMetrics}, in its groups and order. Its Store Size "Total" row reads
     * {@code store.size.total}, the Operations Manual 5 name.
     */
    public static final List<Metric> BROWSER = List.of(
            new Metric("store.size.total", true),
            new Metric("store.size.database", true),
            new Metric("page_cache.hits", false),
            new Metric("page_cache.hit_ratio", false),
            new Metric("page_cache.usage_ratio", false),
            new Metric("page_cache.page_faults", false),
            new Metric("ids_in_use.node", true),
            new Metric("ids_in_use.property", true),
            new Metric("ids_in_use.relationship", true),
            new Metric("ids_in_use.relationship_type", true),
            new Metric("transaction.last_committed_tx_id", true),
            new Metric("transaction.peak_concurrent", true),
            new Metric("transaction.active_read", true),
            new Metric("transaction.active_write", true),
            new Metric("transaction.committed_read", true),
            new Metric("transaction.committed_write", true));

    /** cypher-shell's query ({@code SysInfo.printMetrics}), run once per group with {@code $metrics}. */
    public static final String CYPHER_SHELL_QUERY =
            """
            UNWIND $metrics as metric
            CALL dbms.queryJmx(metric.name) YIELD name, attributes
            WITH metric.displayName AS Name, attributes.Value.value AS value, attributes.Count.value AS count
            RETURN
              Name,
              CASE WHEN value IS NOT NULL then value ELSE count END AS Value""";

    /** The object name cypher-shell builds ({@code Metric.fullName}) with namespaces on, the only form the server has. */
    public static String cypherShellObjectName(String prefix, String database, Metric metric) {
        StringBuilder builder =
                new StringBuilder(prefix + ".metrics").append(":name=").append(prefix);
        if (metric.database()) {
            builder.append(".database").append(".").append(database);
        } else {
            builder.append(".dbms");
        }
        return builder.append(".").append(metric.name()).toString();
    }

    /** One {@code $metrics} entry of the cypher-shell query. */
    public static Map<String, Object> cypherShellParameter(String prefix, String database, Metric metric) {
        return Map.of("name", cypherShellObjectName(prefix, database, metric), "displayName", metric.name());
    }

    /** The Browser's query ({@code constructQuery}), one statement per metric, with namespaces enabled. */
    public static String browserQuery(String prefix, String database, Metric metric, String group) {
        String metricName = (metric.database() ? "database." + database : "dbms") + "." + metric.name();
        return "CALL dbms.queryJmx(\"" + prefix + ".metrics:name=" + prefix + "." + metricName
                + "\") YIELD name, attributes RETURN \"" + group + "\" AS group, name, attributes";
    }
}
