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

/** The server log an administrator reads ({@code debug.log}), where the query log reports its own failures. */
public interface ServerLog {

    void error(String message, Throwable cause);
}
