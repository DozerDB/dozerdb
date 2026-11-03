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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.Config;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.diagnostics.DiagnosticsOfflineReportProvider;
import org.neo4j.kernel.diagnostics.DiagnosticsReportSource;
import org.neo4j.kernel.diagnostics.DiagnosticsReportSources;

/**
 * The diagnostics report provider of the metrics module: {@code neo4j-admin server report} finds it through Java
 * service lookup and asks it for the sources of the {@code metrics} classifier, which Neo4j lists among the report's
 * default classifiers but serves from no Community provider. It extends Neo4j's internal provider base class, so it
 * lives in the upstream adapter.
 *
 * <p>The report runs offline, against a server home, so the provider is given the tool's file system and
 * configuration rather than a running server. It contributes every regular file of the metrics directory
 * ({@code server.directories.metrics}, resolved as the CSV exporter resolves it): the live files and the archives,
 * each under {@code metrics/<file name>} in the report, in name order, as Neo4j's own provider files the logs under
 * {@code logs/}. A home without a metrics directory, or with an empty one, contributes nothing; a directory that
 * cannot be listed yields Neo4j's own {@code metrics/error} note.
 */
@ServiceProvider
public final class MetricsReportProvider extends DiagnosticsOfflineReportProvider {

    /** The classifier {@code neo4j-admin server report} describes as "include metrics". */
    public static final String CLASSIFIER = "metrics";

    /** The folder of the archive that holds the metrics directory's files. */
    public static final String ARCHIVE_FOLDER = CLASSIFIER + "/";

    private FileSystemAbstraction fs;
    private Config config;

    public MetricsReportProvider() {
        super(CLASSIFIER);
    }

    @Override
    public void init(FileSystemAbstraction fs, Config config, Set<String> databaseNames) {
        this.fs = fs;
        this.config = config;
    }

    @Override
    protected List<DiagnosticsReportSource> provideSources(Set<String> classifiers) {
        if (!classifiers.contains(CLASSIFIER)) {
            return List.of();
        }
        Path directory = ConfigAdapter.metricsDirectory(config);
        if (!fs.isDirectory(directory)) {
            return List.of();
        }
        try {
            return Arrays.stream(fs.listFiles(directory, path -> !fs.isDirectory(path)))
                    .sorted()
                    .map(file ->
                            DiagnosticsReportSources.newDiagnosticsFile(ARCHIVE_FOLDER + file.getFileName(), fs, file))
                    .toList();
        } catch (IOException e) {
            return List.of(DiagnosticsReportSources.newDiagnosticsString(
                    ARCHIVE_FOLDER + "error", () -> "Error reading files in directory: " + e.getMessage()));
        }
    }
}
