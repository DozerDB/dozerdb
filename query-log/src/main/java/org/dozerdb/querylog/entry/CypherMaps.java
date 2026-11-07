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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders plain Java values as the Cypher-map text the manual shows for annotation data: unquoted keys in sorted
 * order, single-quoted strings, nested maps and lists, {@code <null>} for a null value, numbers and booleans as
 * Java prints them. The same shape Neo4j's own value printer gives a parameter map, so the two segments read alike.
 */
final class CypherMaps {

    private CypherMaps() {}

    static String render(Map<String, ?> map) {
        return new TreeMap<>(map)
                .entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + renderValue(entry.getValue()))
                        .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String renderValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof String string) {
            return "'" + string + "'";
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> keyed = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> keyed.put(String.valueOf(key), nestedValue));
            return render(keyed);
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(CypherMaps::renderValue).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(value);
    }
}
