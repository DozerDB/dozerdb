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

import java.util.Optional;
import java.util.Set;
import org.dozerdb.metrics.inventory.InventoryEntry.MetricType;

/**
 * Reads a metric's type off the attributes its MBean exposes, the way the consumers of the JMX domain do: a gauge
 * has {@code Value}, a counter {@code Count} alone, a histogram {@code Count} with the percentile attributes
 * ({@code 50thPercentile}, {@code 99thPercentile}, ...), and a timer those plus the rates ({@code MeanRate},
 * {@code OneMinuteRate}, ...). Any other shape (a meter, for example) is no inventory type.
 */
public final class JmxMetricTypes {

    private JmxMetricTypes() {}

    public static Optional<MetricType> fromAttributes(Set<String> attributes) {
        boolean value = attributes.contains("Value");
        boolean count = attributes.contains("Count");
        boolean percentiles = attributes.contains("50thPercentile") && attributes.contains("99thPercentile");
        boolean rates = attributes.contains("MeanRate") && attributes.contains("OneMinuteRate");
        if (value && !count) {
            return Optional.of(MetricType.GAUGE);
        }
        if (count && !value) {
            if (percentiles && rates) {
                return Optional.of(MetricType.TIMER);
            }
            if (percentiles) {
                return Optional.of(MetricType.HISTOGRAM);
            }
            if (!rates) {
                return Optional.of(MetricType.COUNTER);
            }
        }
        return Optional.empty();
    }
}
