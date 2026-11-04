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

/**
 * Receives the first failure of a metrics component (a collector or an exporter, identified by its name). The
 * isolation boundary implements it: it logs once at error level and keeps the component disabled.
 */
@FunctionalInterface
public interface ComponentFailureHandler {

    void componentFailed(String component, Throwable cause);
}
