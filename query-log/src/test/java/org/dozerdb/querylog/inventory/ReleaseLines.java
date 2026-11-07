/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.inventory;

import org.neo4j.kernel.internal.Version;

/**
 * Which release line's statuses of the field inventory apply to the running build, derived from the kernel version
 * the tests run against: Neo4j major {@code 5} is the LTS line, a calendar version ({@code 2026} and later) the
 * calendar line. Nothing in the build differs between branches; the one inventory carries both lines' statuses.
 */
public enum ReleaseLines {
    CALENDAR("calendar"),
    LTS("lts");

    private final String id;

    ReleaseLines(String id) {
        this.id = id;
    }

    /** The line's key in the inventory's {@code lines}, {@code default} and {@code status} objects. */
    public String id() {
        return id;
    }

    /** The line of the Neo4j kernel on the test classpath. */
    public static ReleaseLines current() {
        return of(Version.getKernelVersion());
    }

    /** The line of a kernel version string such as {@code 2026.10.1} or {@code 5.26.32}. */
    public static ReleaseLines of(String kernelVersion) {
        String major = kernelVersion.split("[.-]", 2)[0];
        int number;
        try {
            number = Integer.parseInt(major);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a Neo4j kernel version: '" + kernelVersion + "'", e);
        }
        if (number == 5) {
            return LTS;
        }
        if (number >= 2026) {
            return CALENDAR;
        }
        throw new IllegalArgumentException("No release line for Neo4j major version " + major + " ('" + kernelVersion
                + "'): the query log knows 5 (lts) and 2026+ (calendar)");
    }
}
