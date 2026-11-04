/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.adapter;

/**
 * One database's size on disk as the store size collector reads it at report time, in bytes, from Neo4j's database
 * size service. Each read walks the database's directories, so it is made only when an exporter reports.
 */
public interface StoreSize {

    /** The store files and the transaction logs together. */
    long full();

    /** The store files alone. */
    long database();

    /** Neo4j's estimate of space already reserved in the store files that new data can reuse. */
    long availableReserved();
}
