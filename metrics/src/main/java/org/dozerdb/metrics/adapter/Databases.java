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

import java.util.List;
import java.util.Optional;

/**
 * The server's databases as the metrics module sees them: which are started, the lifecycle events of each, and the
 * dependencies of a started one. Database metrics are created and retired through it from the global extension,
 * and the database operation counts are kept from its events. The extension registers one listener and then asks
 * for the databases already started, because a database (the system database in particular) may have started
 * before the global extension did.
 */
public interface Databases {

    /** Delivers every database lifecycle event from now on to the listener. */
    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** The names of the databases started at this moment. */
    List<String> started();

    /** The dependencies of the named database while it is started; empty when it is not started or unknown. */
    Optional<DatabaseDependencies> dependencies(String databaseName);

    /** A snapshot of how many registered databases are in each state the state count metrics report. */
    StateCounts stateCounts();

    /**
     * The database state counts (Operations Manual 5, "Database state count metrics").
     *
     * @param hosted the databases that are started
     * @param failed the databases whose last operation failed, or that panicked, and have not been stopped since
     * @param desiredStarted the databases that are started or failed to start
     */
    record StateCounts(long hosted, long failed, long desiredStarted) {}

    /**
     * Receives the server's database lifecycle events, one method per public database event, on the thread that
     * performs the operation. A listener implements what it needs; the rest are no-ops.
     */
    interface Listener {

        /**
         * The database is being created: it has been initialised for the first time and has no store yet. A database
         * that already has a store (an existing database a server starts, or one started again after a stop) is not
         * announced, although Neo4j raises its create event for it too.
         */
        default void databaseCreated(String databaseName) {}

        /** The database is completely operational and its dependencies can be resolved. */
        void databaseStarted(String databaseName);

        /** The database is about to stop; its metrics are no longer available. */
        default void databaseStopped(String databaseName) {}

        /** The database has been dropped; a started database is stopped first, which is announced separately. */
        default void databaseDropped(String databaseName) {}

        /** The database has entered a state from which it cannot recover and continue. */
        default void databasePanicked(String databaseName) {}
    }
}
