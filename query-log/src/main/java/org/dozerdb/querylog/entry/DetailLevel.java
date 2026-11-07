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
 * The detail level of the query log, the value of {@code db.logs.query.enabled} (and, for transaction entries, of
 * {@code db.logs.query.transaction.enabled}) in the module's own words, so that no Neo4j enum leaves the upstream
 * adapter. {@code OFF} writes nothing and never reaches a record; an entry carries the level it was written under,
 * because the plain line shows the {@code id:} prefix and the JSON entry its {@code id} field at {@code VERBOSE} only,
 * as the manual's JSON field list says.
 */
public enum DetailLevel {
    OFF,
    INFO,
    VERBOSE
}
