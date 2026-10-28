/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */
package org.neo4j.server;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.server.startup.EntryPoint;

/**
 * The DozerDbEntryPoint class is the entry point for the DozerDb bootstrapping mechanism for Neo4j.
 * It's designed to manage the bootstrapping process, starting, and stopping the underlying services.
 * <p>
 * <p>
 * The @ServiceProvider annotation marks the class as a service provider, making it discoverable within the Neo4j server environment.
 */
@ServiceProvider
public class DozerDbEntryPoint implements EntryPoint {

    private static Bootstrapper bootstrapper;

    /**
     * The main entry point for the application. It calls the static start method of NeoBootstrapper with an instance of DozerDbBootstrapper
     * and exits with the bootstrap status code.
     *
     * @param arguments
     */
    public static void main(String[] arguments) {
        int bootstrapStatus = NeoBootstrapper.start(new DozerDbBootstrapper(), arguments);
        if (bootstrapStatus != 0) {
            System.exit(bootstrapStatus);
        }
    }

    /**
     * This method is marked to be used by a Windows service wrapper. It initializes the bootstrapper with a BlockingBootstrapper instance wrapped around DozerDbBootstrapper and then starts it.
     * The process exits with the return value of NeoBootstrapper.start.
     */
    @SuppressWarnings("unused")
    public static void start(String[] arguments) {
        bootstrapper = new BlockingBootstrapper(new DozerDbBootstrapper());
        System.exit(NeoBootstrapper.start(bootstrapper, arguments));
    }

    /**
     * Another method for the Windows service wrapper, it stops the bootstrapper if it is not null.
     */
    @SuppressWarnings("unused")
    public static void stop(@SuppressWarnings("UnusedParameters") String[] arguments) {
        if (bootstrapper != null) {
            bootstrapper.stop();
        }
    }

    /**
     * Ranks ahead of Neo4j Community's entry point. The bootloader picks the provider with the lowest
     * priority value, and launchers that pass {@code lib/*} (Windows, or a stock Neo4j with the plugin
     * JAR in {@code lib}) give no classpath ordering guarantee, so DozerDB must not tie with community.
     */
    @Override
    public int getPriority() {
        return Priority.MEDIUM.ordinal();
    }
}
