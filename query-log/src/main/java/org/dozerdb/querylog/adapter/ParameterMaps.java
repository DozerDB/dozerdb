/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import org.neo4j.values.AnyValue;
import org.neo4j.values.AnyValueWriter.EntityMode;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.utils.PrettyPrinter;
import org.neo4j.values.virtual.MapValue;

/**
 * Renders a query's parameter map as the parameters segment of an entry: the manual's single-line Cypher map with
 * single quotes ({@code {foo: 'bar', baz: {k: 1}}}, {@code {}} when there are none), which is Neo4j's own
 * {@code PrettyPrinter} with the single quote mark and entities by reference, since no other Community printer yields
 * Cypher map syntax. Keys are written unquoted and in the map's own order, as the printer writes a whole map.
 *
 * <p>{@code db.logs.query.max_parameter_length} is the manual's "maximum character length for each parameter": every
 * value gets its own budget, never the map as a whole, so one large value cannot hide the others. A value whose text
 * exceeds the budget is cut to that many characters and {@code ...} is appended inside the value: a string keeps its
 * closing quote ({@code 'Alexander ...'}), so the entry still reads as a map, and a list or map is cut in its text
 * ({@code [100, 200...}). The printer's own budget is not used because it drops the closing quote and brace once the
 * budget is spent and, when the budget ends exactly at a chunk boundary, writes no {@code ...} at all. The cut falls
 * between characters, never inside a surrogate pair. The default maximum is {@code Integer.MAX_VALUE}, under which
 * nothing is cut.
 */
final class ParameterMaps {

    private static final String QUOTE = "'";
    private static final String ELLIPSIS = "...";

    private ParameterMaps() {}

    static String render(MapValue parameters, int maxParameterLength) {
        StringBuilder map = new StringBuilder(64).append('{');
        PrettyPrinter printer = new PrettyPrinter(QUOTE, EntityMode.REFERENCE);
        boolean[] first = {true};
        parameters.foreach((key, value) -> {
            if (!first[0]) {
                map.append(", ");
            }
            first[0] = false;
            map.append(key).append(": ");
            appendValue(map, value, printer, maxParameterLength);
        });
        return map.append('}').toString();
    }

    private static void appendValue(StringBuilder into, AnyValue value, PrettyPrinter printer, int maxLength) {
        if (value instanceof TextValue text) {
            // The printer wraps a string verbatim in the quote mark with no escaping; the cut keeps both quotes.
            into.append(QUOTE).append(cut(text.stringValue(), maxLength)).append(QUOTE);
            return;
        }
        printer.reset();
        value.writeTo(printer);
        into.append(cut(printer.value(), maxLength));
    }

    /** The first {@code maxLength} characters followed by {@code ...} when the text is longer, else the text. */
    private static String cut(String text, int maxLength) {
        if (maxLength < 0 || text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + ELLIPSIS;
    }
}
