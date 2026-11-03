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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;

/**
 * Reads metrics the way an administrator, cypher-shell {@code :sysinfo} and the Browser do: through
 * {@code CALL dbms.queryJmx(...)} against the platform MBean server. Tests observe nothing else.
 */
public final class JmxQueries {

    private JmxQueries() {}

    /** Canonical object names matching an {@link javax.management.ObjectName} pattern, for example {@code neo4j.metrics:*}. */
    public static Set<String> names(GraphDatabaseService db, String pattern) {
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> row : rows(db, pattern)) {
            names.add((String) row.get("name"));
        }
        return names;
    }

    /** The {@code Value} attribute of one gauge MBean, or {@code null} when no MBean matches. */
    public static Object value(GraphDatabaseService db, String objectName) {
        return attribute(db, objectName, "Value");
    }

    /** One attribute's value of one MBean, or {@code null} when no MBean matches. */
    @SuppressWarnings("unchecked")
    public static Object attribute(GraphDatabaseService db, String objectName, String attribute) {
        List<Map<String, Object>> rows = rows(db, objectName);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> attributes = (Map<String, Object>) rows.get(0).get("attributes");
        Map<String, Object> entry = (Map<String, Object>) attributes.get(attribute);
        return entry == null ? null : entry.get("value");
    }

    /** Every MBean matching the pattern with the names of its attributes, keyed by canonical object name. */
    @SuppressWarnings("unchecked")
    public static Map<String, Set<String>> attributeNames(GraphDatabaseService db, String pattern) {
        Map<String, Set<String>> result = new TreeMap<>();
        for (Map<String, Object> row : rows(db, pattern)) {
            Map<String, Object> attributes = (Map<String, Object>) row.get("attributes");
            result.put((String) row.get("name"), new TreeSet<>(attributes.keySet()));
        }
        return result;
    }

    public static List<Map<String, Object>> rows(GraphDatabaseService db, String pattern) {
        try (Transaction tx = db.beginTx()) {
            return tx
                    .execute(
                            "CALL dbms.queryJmx($query) YIELD name, attributes RETURN name, attributes",
                            Map.of("query", pattern))
                    .stream()
                    .toList();
        }
    }
}
