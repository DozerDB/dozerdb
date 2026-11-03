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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;

/**
 * Compares the metrics a server exposes in its JMX domain with the metrics the inventory says it emits. The
 * registry may contain only names the inventory's Compatible and Partial rows produce, must contain all of them,
 * and each MBean must have the attribute shape of the row's type.
 */
final class InventoryConformance {

    private InventoryConformance() {}

    /**
     * @param expected the emitted inventory rows expanded against the server
     * @param observed every full metric name in the JMX domain with the attribute names of its MBean
     */
    static Report compare(List<ExpectedMetric> expected, Map<String, Set<String>> observed) {
        Set<String> extra = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        List<String> typeMismatches = new ArrayList<>();
        Set<ExpectedMetric> matched = new java.util.HashSet<>();
        for (Map.Entry<String, Set<String>> mbean : new TreeMap<>(observed).entrySet()) {
            String name = mbean.getKey();
            List<ExpectedMetric> matches = expected.stream()
                    .filter(candidate -> candidate.matches(name))
                    .toList();
            if (matches.isEmpty()) {
                extra.add(name);
                problems.add(name + ": registered but no Compatible or Partial inventory row produces it");
                continue;
            }
            if (matches.size() > 1) {
                matched.addAll(matches);
                problems.add(name + ": matches several inventory rows "
                        + matches.stream().map(ExpectedMetric::description).toList());
                continue;
            }
            ExpectedMetric match = matches.get(0);
            matched.add(match);
            MetricType inventoryType = match.entry().type();
            Optional<MetricType> observedType = JmxMetricTypes.fromAttributes(mbean.getValue());
            if (observedType.isEmpty() || observedType.get() != inventoryType) {
                String mismatch = name + ": inventory says " + inventoryType.label() + ", MBean attributes "
                        + new TreeSet<>(mbean.getValue()) + " are "
                        + observedType.map(type -> "a " + type.label()).orElse("no inventory type");
                typeMismatches.add(mismatch);
                problems.add(mismatch);
            }
        }
        List<String> missing = new ArrayList<>();
        for (ExpectedMetric candidate : expected) {
            if (!matched.contains(candidate)) {
                missing.add(candidate.description());
                problems.add(candidate.description() + ": "
                        + candidate.entry().tier().label() + " in the inventory but not registered");
            }
        }
        return new Report(extra, missing, typeMismatches, problems);
    }

    /**
     * @param extra registered names no emitted row produces
     * @param missing expected names (instance placeholders still visible) no MBean matches
     * @param typeMismatches MBeans whose attribute shape disagrees with the inventory's type
     * @param problems every finding as one line, empty when the server conforms
     */
    record Report(Set<String> extra, List<String> missing, List<String> typeMismatches, List<String> problems) {}
}
