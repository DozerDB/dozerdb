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

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dozerdb.metrics.settings.MetricsSettings.CompressionOption;

/**
 * The size-based rotation of one metric's CSV file: {@code server.metrics.csv.rotation.size} (zero never
 * rotates), {@code keep_number} archives per metric and their {@code compression}.
 *
 * <p>Archives are {@code <name>.csv.1} (newest) to {@code <name>.csv.N}; a compressed archive carries the
 * compression extension after the index, {@code <name>.csv.1.zip} or {@code <name>.csv.1.gz}, the convention
 * of logrotate and Log4j. A ZIP archive holds one entry named like the live file ({@code <name>.csv}); a GZ
 * archive is the live file's bytes. When the live file has reached the threshold, the archive at
 * {@code keep_number} is deleted, every other archive shifts up by one index whatever its compression, the live
 * file becomes {@code .1}, and the next row starts a fresh live file with the header.
 *
 * @param sizeThreshold the live file size in bytes at which it is archived; zero disables rotation
 * @param keepNumber how many archives of one metric are kept
 * @param compression how archives are compressed
 */
public record CsvRotation(long sizeThreshold, int keepNumber, CompressionOption compression) {

    /** Archives the live file if it has reached the threshold; the next row then starts a fresh file. */
    void rotateIfDue(Path liveFile) throws IOException {
        if (sizeThreshold <= 0 || Files.notExists(liveFile) || Files.size(liveFile) < sizeThreshold) {
            return;
        }
        for (CompressionOption option : CompressionOption.values()) {
            Files.deleteIfExists(archive(liveFile, keepNumber, option));
        }
        for (int index = keepNumber - 1; index >= 1; index--) {
            for (CompressionOption option : CompressionOption.values()) {
                Path from = archive(liveFile, index, option);
                if (Files.exists(from)) {
                    Files.move(from, archive(liveFile, index + 1, option), REPLACE_EXISTING);
                }
            }
        }
        archiveLive(liveFile, archive(liveFile, 1, compression));
    }

    private void archiveLive(Path liveFile, Path archive) throws IOException {
        switch (compression) {
            case NONE -> Files.move(liveFile, archive, REPLACE_EXISTING);
            case GZ -> {
                try (InputStream in = Files.newInputStream(liveFile);
                        OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
                    in.transferTo(out);
                }
                Files.delete(liveFile);
            }
            case ZIP -> {
                try (InputStream in = Files.newInputStream(liveFile);
                        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
                    out.putNextEntry(new ZipEntry(liveFile.getFileName().toString()));
                    in.transferTo(out);
                    out.closeEntry();
                }
                Files.delete(liveFile);
            }
        }
    }

    /** {@code <live file>.<index>} plus the compression's extension. */
    static Path archive(Path liveFile, int index, CompressionOption compression) {
        return liveFile.resolveSibling(liveFile.getFileName() + "." + index + extension(compression));
    }

    static String extension(CompressionOption compression) {
        return switch (compression) {
            case NONE -> "";
            case ZIP -> ".zip";
            case GZ -> ".gz";
        };
    }
}
