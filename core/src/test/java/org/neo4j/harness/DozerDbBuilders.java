/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.harness;

import java.nio.file.Path;
import org.neo4j.harness.internal.DozerDbInProcessNeo4jBuilder;

/**
 * Factory for creating {@link Neo4jBuilder} instances that use the DozerDb enterprise edition module.
 *
 * <p>Mirrors {@link Neo4jBuilders} but produces builders wired with
 * {@code DozerDbEditionModule} instead of {@code CommunityEditionModule}.</p>
 */
public final class DozerDbBuilders {

    /**
     * Creates a builder capable of starting an in-process DozerDb instance.
     * Uses the standard Java temp directory as the location for temporary Neo4j data.
     *
     * @return a new {@link Neo4jBuilder} backed by the DozerDb edition module
     */
    public static Neo4jBuilder newInProcessBuilder() {
        return new DozerDbInProcessNeo4jBuilder();
    }

    /**
     * Creates a builder capable of starting an in-process DozerDb instance,
     * running in a subdirectory of the specified directory.
     *
     * @param workingDirectory the parent directory for the temporary Neo4j data
     * @return a new {@link Neo4jBuilder} backed by the DozerDb edition module
     */
    public static Neo4jBuilder newInProcessBuilder(final Path workingDirectory) {
        return new DozerDbInProcessNeo4jBuilder(workingDirectory);
    }

    private DozerDbBuilders() {}
}
