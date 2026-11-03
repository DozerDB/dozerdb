/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.registry;

import java.util.List;
import org.neo4j.configuration.helpers.GlobbingPattern;

/**
 * Decides which full metric names are registered: a name must match a {@code server.metrics.filter} pattern and
 * no {@code server.metrics.deny_filter} pattern. Patterns are Neo4j's globs ({@code *} any sequence, {@code ?} one
 * character) matched against the whole full name, evaluated once per registration. An empty filter admits
 * nothing, because only matching metrics are enabled.
 *
 * <p>Neo4j's own {@link GlobbingPattern} (public API) does the matching, so the semantics are exactly those of
 * the settings reference.
 */
public final class MetricNameFilter {

    private final List<GlobbingPattern> filter;
    private final List<GlobbingPattern> denyFilter;

    public MetricNameFilter(List<String> filter, List<String> denyFilter) {
        this.filter = filter.stream().map(GlobbingPattern::new).toList();
        this.denyFilter = denyFilter.stream().map(GlobbingPattern::new).toList();
    }

    /** A filter that admits every name. */
    public static MetricNameFilter all() {
        return new MetricNameFilter(List.of("*"), List.of());
    }

    public boolean accepts(String fullName) {
        return filter.stream().anyMatch(pattern -> pattern.matches(fullName))
                && denyFilter.stream().noneMatch(pattern -> pattern.matches(fullName));
    }
}
