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

/**
 * The value of {@code db.logs.query.annotation_data_format} in the module's own words, so that no Neo4j enum leaves
 * the upstream adapter. It changes how the JSON layout writes a transaction's metadata; the plain line always writes
 * a Cypher map.
 */
public enum AnnotationDataFormat {
    CYPHER,
    JSON,
    FLAT_JSON
}
