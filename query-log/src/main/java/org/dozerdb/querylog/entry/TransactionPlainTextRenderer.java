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

import static org.dozerdb.querylog.entry.Rendering.appendText;

/**
 * Renders a transaction entry as the {@code %m} of the plain-text line; the layout adds the timestamp and the level.
 * The Operations Manual documents no plain-text transaction entry, so this grammar is DozerDB's, built from the
 * segments a query entry already has in the order a reader of query entries expects, segments joined by {@code " - "}
 * except the tab between the source and the database:
 *
 * <pre>
 * Transaction started: |Transaction committed: |Transaction rolled back:  transaction id:N - N ms
 *   - SOURCE\tDATABASE - USER - ANNOTATIONS[ - FAILURE REASON]
 * </pre>
 *
 * <p>The failure reason closes a rollback caused by a failure; the throwable itself goes to Log4j for the stack
 * trace, not through here. An absent field leaves its segment empty.
 */
public final class TransactionPlainTextRenderer {

    private TransactionPlainTextRenderer() {}

    public static String render(TransactionEntry entry) {
        StringBuilder line = new StringBuilder(160);
        line.append(markerOf(entry.kind()))
                .append("transaction id:")
                .append(entry.transactionId())
                .append(" - ")
                .append(entry.openMillis())
                .append(" ms - ");
        appendText(line, entry.source());
        line.append('\t');
        appendText(line, entry.database());
        line.append(" - ");
        appendText(line, entry.executingUser());
        line.append(" - ").append(CypherMaps.render(entry.annotationData()));
        if (entry.failed() && entry.failureReason() != null) {
            line.append(" - ").append(entry.failureReason());
        }
        return line.toString();
    }

    private static String markerOf(TransactionEntry.Kind kind) {
        return switch (kind) {
            case START -> "Transaction started: ";
            case COMMIT -> "Transaction committed: ";
            case ROLLBACK -> "Transaction rolled back: ";
        };
    }
}
