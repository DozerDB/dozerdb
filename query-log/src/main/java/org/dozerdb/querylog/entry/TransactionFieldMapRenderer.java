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

/**
 * Renders a transaction entry as the field map of Neo4j's query log JSON template: the keys the Operations Manual
 * lists for {@code type: transaction}, with the JSON type the manual's examples show ({@code Long} for the
 * transaction id, {@code String} for the rest), plus {@code failureReason} on a rollback caused by a failure, the key
 * a failed-query entry carries its reason under, so that a parser keyed on it finds a failed transaction the same way
 * (the layout's {@code stacktrace} alone would leave the reason inside the trace text). The layout adds {@code time},
 * {@code level} and that {@code stacktrace} from the log event, so none of them is a key here. The open time, the
 * source and the annotation data are plain-text segments only: the manual lists none of them for a transaction
 * entry, and a parser of Neo4j's transaction entries would not expect them.
 *
 * <p>A field the entry does not carry is absent from the map, never present with a {@code null} value, because Log4j
 * would write {@code "key":null}, which is not what Neo4j's entries show.
 */
public final class TransactionFieldMapRenderer {

    private TransactionFieldMapRenderer() {}

    public static Map<String, Object> render(TransactionEntry entry) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "transaction");
        fields.put("event", eventOf(entry.kind()));
        fields.put("transactionId", entry.transactionId());
        putIfPresent(fields, "database", entry.database());
        putIfPresent(fields, "executingUser", entry.executingUser());
        putIfPresent(fields, "authenticatedUser", entry.authenticatedUser());
        if (entry.failed()) {
            putIfPresent(fields, "failureReason", entry.failureReason());
        }
        return fields;
    }

    private static String eventOf(TransactionEntry.Kind kind) {
        return switch (kind) {
            case START -> "start";
            case COMMIT -> "commit";
            case ROLLBACK -> "rollback";
        };
    }
}
