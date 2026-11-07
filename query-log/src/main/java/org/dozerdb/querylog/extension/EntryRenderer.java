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

import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.QueryEntry;
import org.dozerdb.querylog.entry.TransactionEntry;

/**
 * Turns an entry into its rendering for both layouts, the plain line and the JSON field map; a query entry under the
 * annotation data format in force, a transaction entry on its own (its JSON carries no annotation data). The
 * production renderer is {@link RenderedEntry#of}; tests of the isolation boundary inject one that throws, and tests
 * of the {@code OFF} fast path one that counts. The transaction rendering has a default so that a renderer written for
 * query entries alone still renders transaction entries the production way.
 */
@FunctionalInterface
public interface EntryRenderer {

    RenderedEntry render(QueryEntry entry, AnnotationDataFormat annotationDataFormat);

    default RenderedEntry render(TransactionEntry entry) {
        return RenderedEntry.of(entry);
    }
}
