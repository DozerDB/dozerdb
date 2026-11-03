/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

/**
 * An exporter publishes the registered metrics through one channel: JMX, CSV, Prometheus or Graphite. Its name
 * identifies it in the log.
 */
public interface Exporter {

    String name();

    void start() throws Exception;

    void stop() throws Exception;
}
