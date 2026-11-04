/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One inventory row expanded against a running server: {@code <prefix>} replaced by the configured prefix,
 * {@code <db>} by one of the server's databases, and the instance placeholders ({@code <pool>}, {@code <gc>},
 * {@code <bufferpool>}) left to match any instance name the JVM provides, so the row expects at least one MBean.
 *
 * @param entry the inventory row
 * @param description the expanded name, instance placeholders still visible
 * @param pattern the full names this expectation matches
 */
record ExpectedMetric(InventoryEntry entry, String description, Pattern pattern) {

    private static final Pattern INSTANCE_PLACEHOLDER = Pattern.compile("<(pool|gc|bufferpool)>");

    /** Expands every row once per database it applies to: global rows once, database rows once per database. */
    static List<ExpectedMetric> expand(List<InventoryEntry> entries, String prefix, List<String> databases) {
        List<ExpectedMetric> expected = new ArrayList<>();
        for (InventoryEntry entry : entries) {
            String template = entry.fullNameTemplate().replace(InventoryEntry.PREFIX_PLACEHOLDER, prefix);
            if (entry.scope() == InventoryEntry.Scope.DATABASE) {
                for (String database : databases) {
                    expected.add(of(entry, template.replace(InventoryEntry.DATABASE_PLACEHOLDER, database)));
                }
            } else {
                expected.add(of(entry, template));
            }
        }
        return expected;
    }

    private static ExpectedMetric of(InventoryEntry entry, String expanded) {
        StringBuilder regex = new StringBuilder();
        Matcher placeholders = INSTANCE_PLACEHOLDER.matcher(expanded);
        int literalStart = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(expanded.substring(literalStart, placeholders.start())))
                    .append("[^.]+");
            literalStart = placeholders.end();
        }
        regex.append(Pattern.quote(expanded.substring(literalStart)));
        return new ExpectedMetric(entry, expanded, Pattern.compile(regex.toString()));
    }

    boolean matches(String fullName) {
        return pattern.matcher(fullName).matches();
    }
}
