/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.FieldMapRenderer;
import org.dozerdb.querylog.entry.PlainTextRenderer;
import org.dozerdb.querylog.entry.QueryEntry;
import org.dozerdb.querylog.entry.TransactionEntry;
import org.dozerdb.querylog.entry.TransactionFieldMapRenderer;
import org.dozerdb.querylog.entry.TransactionPlainTextRenderer;

/**
 * One entry rendered for both query log layouts at once: the {@code %m} of the plain line and the field map of the
 * JSON template. The operator's {@code server-logs.xml} decides which of the two the file shows, and the module
 * cannot know which, so every entry is rendered both ways, once, on the thread that ends the query, and handed to the
 * upstream adapter, which wraps it in Neo4j's map message: the message's string form is {@link #line()} and its map
 * is {@link #fields()}. The two renderers of the entry model are the only producers of a regular entry; the fallback
 * entry of the isolation boundary is built here directly, so that a broken renderer cannot break the entry that
 * reports it.
 *
 * @param line the plain line's {@code %m}
 * @param fields the JSON field map, an absent field absent rather than {@code null}
 */
public record RenderedEntry(String line, Map<String, Object> fields) {

    public RenderedEntry {
        Objects.requireNonNull(line, "line");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** The production rendering: the plain-text renderer and the field-map renderer over the same record. */
    public static RenderedEntry of(QueryEntry entry, AnnotationDataFormat annotationDataFormat) {
        return new RenderedEntry(PlainTextRenderer.render(entry), FieldMapRenderer.render(entry, annotationDataFormat));
    }

    /** The production rendering of a transaction entry: its plain-text renderer and its field-map renderer. */
    public static RenderedEntry of(TransactionEntry entry) {
        return new RenderedEntry(TransactionPlainTextRenderer.render(entry), TransactionFieldMapRenderer.render(entry));
    }

    /**
     * A fallback entry: the plain text names the query id and the failure, and the JSON carries the same text as the
     * failure reason of a {@code query} entry with that {@code id}, so the gap is attributable in either layout.
     */
    static RenderedEntry fallback(long queryId, String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "query");
        fields.put("id", Long.toString(queryId));
        fields.put("failureReason", text);
        return new RenderedEntry(text, fields);
    }

    /**
     * The fallback entry for a transaction entry: the plain text names the transaction id and the failure, and the
     * JSON is a {@code transaction} entry with that {@code transactionId} and the text as its failure reason.
     */
    static RenderedEntry transactionFallback(long transactionId, String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "transaction");
        fields.put("transactionId", transactionId);
        fields.put("failureReason", text);
        return new RenderedEntry(text, fields);
    }
}
