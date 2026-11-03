/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.collectors;

import java.util.Locale;

/**
 * The instance segment of a JVM metric name: the {@code <gc>}, {@code <pool>} and {@code <bufferpool>} placeholders
 * of the Operations Manual, resolved from the MXBean name of a garbage collector, memory pool or buffer pool.
 *
 * <p>The manual spells its examples {@code g1_eden_space}, {@code g1_old_gen}, {@code g1_survivor_space} and
 * {@code direct}, Aura's reference {@code g1_young_generation}: the MXBean name lower-cased with its spaces turned
 * into underscores. The rule applied here is that spelling, generalised to the names the manual shows no example
 * for ({@code CodeHeap 'non-nmethods'}, {@code mapped - 'non-volatile memory'}): lower case, every run of characters
 * that are not ASCII letters or digits becomes one underscore, and no underscore leads or trails
 * ({@code codeheap_non_nmethods}, {@code mapped_non_volatile_memory}). Enterprise's spelling of those is unverified;
 * the inventory notes record this rule.
 */
final class JvmInstanceNames {

    private JvmInstanceNames() {}

    static String of(String mxBeanName) {
        String name = mxBeanName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) == '_') {
            start++;
        }
        while (end > start && name.charAt(end - 1) == '_') {
            end--;
        }
        return name.substring(start, end);
    }
}
