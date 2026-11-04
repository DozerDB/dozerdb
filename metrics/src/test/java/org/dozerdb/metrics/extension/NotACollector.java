/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.extension;

/**
 * A class that is not a collector, named in the collector seam by the isolation test: the seam must reject it
 * without initialising it, so its static initialiser never runs.
 */
public final class NotACollector {

    /** Set by the static initialiser; the test reads it to learn whether the seam initialised the class. */
    public static final String INITIALISED_PROPERTY = "dozerdb.metrics.test.not_a_collector_initialised";

    static {
        System.setProperty(INITIALISED_PROPERTY, "true");
    }

    private NotACollector() {}
}
