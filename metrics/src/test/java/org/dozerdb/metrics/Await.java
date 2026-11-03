/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Bounded polling for the exporter tests: an exporter writes on its own schedule, so a test waits for the
 * external surface (a file, a row) to show the expected state, and fails with the description once the timeout
 * has passed.
 */
public final class Await {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private Await() {}

    public static void until(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting until " + description);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting until " + description, e);
            }
        }
    }
}
