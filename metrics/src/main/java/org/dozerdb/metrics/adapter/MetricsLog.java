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

/** The module's view of the server log: the isolation boundary reports through it, nothing else needs more. */
public interface MetricsLog {

    void info(String message);

    void error(String message, Throwable cause);
}
