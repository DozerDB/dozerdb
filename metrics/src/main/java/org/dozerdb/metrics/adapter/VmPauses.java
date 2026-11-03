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

import java.util.function.LongConsumer;

/**
 * The VM pauses Neo4j's own pause monitor detects, as the pause time collector hears of them: Neo4j measures the
 * JVM's stalls on a scheduler thread of its own and announces each one on the global monitor bus with its length in
 * milliseconds. The collector listens while it is registered and gives the listener back when it is retired.
 */
public interface VmPauses {

    /** Delivers the milliseconds of every detected pause, on the pause monitor's thread, from now on. */
    void observePauses(LongConsumer pauseMillis);

    /** Stops listening. */
    void stopObservingPauses();
}
