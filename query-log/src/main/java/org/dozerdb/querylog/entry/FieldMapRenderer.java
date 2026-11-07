/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.entry;

import static org.dozerdb.querylog.entry.Rendering.putIfPresent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders a query entry as the field map of Neo4j's query log JSON template: the keys the Operations Manual lists for
 * {@code type: query}, in the manual's order, with the JSON type the manual's examples show ({@code Long} for the
 * figures, {@code String} for the text, a nested map for {@code errorInfo}). The layout itself adds {@code time},
 * {@code level} and {@code stacktrace} from the log event, so none of them is a key here. The map message the adapter
 * logs puts every entry of this map into Log4j's sorted map, so the file shows the keys in sorted order, whatever the
 * order here.
 *
 * <p>A field the entry does not carry is absent from the map, never present with a {@code null} value: Log4j would
 * write {@code "key":null}, which is not what Neo4j's entries show. {@code cpu} is therefore present only when Neo4j
 * tracked the CPU time, {@code queryParameters} only when parameter logging is on, {@code queryLang} only on a line
 * that knows the Cypher version. {@code id} appears at {@code VERBOSE} only, as in the plain line, and as a string,
 * which is how Neo4j's own query object exposes its id.
 */
public final class FieldMapRenderer {

    private FieldMapRenderer() {}

    public static Map<String, Object> render(QueryEntry entry, AnnotationDataFormat annotationDataFormat) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "query");
        fields.put("event", eventOf(entry.kind()));
        if (entry.detailLevel() == DetailLevel.VERBOSE) {
            fields.put("id", Long.toString(entry.queryId()));
        }
        fields.put("transactionId", entry.transactionId());
        fields.put("elapsedTimeMs", entry.elapsedMillis());
        fields.put("planning", entry.planningMillis());
        putIfPresent(fields, "cpu", entry.cpuMillis());
        fields.put("waiting", entry.waitingMillis());
        fields.put("allocatedBytes", entry.allocatedBytes());
        fields.put("pageHits", entry.pageHits());
        fields.put("pageFaults", entry.pageFaults());
        putIfPresent(fields, "source", entry.source());
        putIfPresent(fields, "database", entry.database());
        putIfPresent(fields, "executingUser", entry.executingUser());
        putIfPresent(fields, "authenticatedUser", entry.authenticatedUser());
        putIfPresent(fields, "query", entry.queryText());
        putIfPresent(fields, "queryParameters", entry.parameters());
        putIfPresent(fields, "runtime", entry.runtime());
        fields.put("annotationData", annotationData(entry.annotationData(), annotationDataFormat));
        if (entry.failed()) {
            putIfPresent(fields, "failureReason", entry.failureReason());
            putIfPresent(fields, "errorInfo", entry.errorInfo());
        }
        putIfPresent(fields, "queryPlan", entry.queryPlan());
        putIfPresent(fields, "executionPlanCacheKeyHash", entry.executionPlanCacheKeyHash());
        putIfPresent(fields, "queryLang", entry.queryLanguage());
        return fields;
    }

    /**
     * The transaction's metadata as {@code db.logs.query.annotation_data_format} asks for it: the Cypher map text of
     * the plain line, a JSON object, or a JSON object whose nested maps are folded into dotted top-level keys
     * ({@code {"baz": {"k": 1}}} becomes {@code {"baz.k": 1}}). Keys are sorted in every format, as in the plain
     * line, because the metadata arrives in no order a parser could rely on.
     */
    private static Object annotationData(Map<String, Object> annotationData, AnnotationDataFormat format) {
        return switch (format) {
            case CYPHER -> CypherMaps.render(annotationData);
            case JSON -> sorted(annotationData);
            case FLAT_JSON -> flattened("", annotationData, new LinkedHashMap<>());
        };
    }

    private static Map<String, Object> sorted(Map<?, ?> map) {
        Map<String, Object> sorted = new LinkedHashMap<>();
        new TreeMap<>(stringKeyed(map))
                .forEach((key, value) -> sorted.put(key, value instanceof Map<?, ?> nested ? sorted(nested) : value));
        return sorted;
    }

    private static Map<String, Object> flattened(String prefix, Map<?, ?> map, Map<String, Object> into) {
        new TreeMap<>(stringKeyed(map)).forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                flattened(prefix + key + ".", nested, into);
            } else {
                into.put(prefix + key, value);
            }
        });
        return into;
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        map.forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return keyed;
    }

    private static String eventOf(QueryEntry.Kind kind) {
        return switch (kind) {
            case START -> "start";
            case SUCCESS -> "success";
            case FAIL -> "fail";
        };
    }
}
