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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the upstream adapter boundary: Neo4j types that are not public API are imported only inside
 * {@code org.dozerdb.metrics.adapter}, so that a Neo4j upgrade can break only that package. The adapter also holds
 * the module's Neo4j entry points (the extension factory, the diagnostics report provider and the internal
 * settings), because each must extend or be built from Neo4j internals.
 *
 * <p>The other packages may name only the Neo4j classes in {@link #ALLOWED}: classes annotated {@code @PublicApi} at
 * the pinned tag, and one narrow exception. {@code MetricsSettings}, the published settings declaration whose class
 * name is public API, is built the only way Neo4j declares settings: with {@code SettingImpl},
 * {@code SettingValueParsers}, {@code SettingConstraints} and the {@code Description} annotation, registered through
 * the compile-time {@code ServiceProvider} annotation and marked with {@code PublicApi}. Those six may appear in the
 * {@code settings} package and nowhere else outside the adapter.
 *
 * <p>Every {@code org.neo4j} reference in the main sources is checked, imports and inline names alike, after
 * comments and string literals are removed.
 */
class UpstreamAdapterBoundaryTest {

    private static final String ROOT_PACKAGE = "org.dozerdb.metrics";
    private static final String ADAPTER_PACKAGE = ROOT_PACKAGE + ".adapter";

    /** Allowed Neo4j classes per package (a class allows its static members and nested types too). */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            ROOT_PACKAGE + ".settings",
                    Set.of(
                            // @PublicApi
                            "org.neo4j.configuration.GraphDatabaseSettings",
                            "org.neo4j.configuration.SettingsDeclaration",
                            "org.neo4j.configuration.helpers.GlobbingPattern",
                            "org.neo4j.configuration.helpers.SocketAddress",
                            "org.neo4j.graphdb.config.Setting",
                            // The settings declaration's exception: the builders and annotations it cannot avoid
                            "org.neo4j.annotations.api.PublicApi",
                            "org.neo4j.annotations.service.ServiceProvider",
                            "org.neo4j.configuration.Description",
                            "org.neo4j.configuration.SettingConstraints",
                            "org.neo4j.configuration.SettingImpl",
                            "org.neo4j.configuration.SettingValueParsers"),
            ROOT_PACKAGE + ".registry", Set.of("org.neo4j.configuration.helpers.GlobbingPattern"), // @PublicApi
            ROOT_PACKAGE + ".extension", Set.of(),
            ROOT_PACKAGE + ".collectors", Set.of(),
            ROOT_PACKAGE + ".exporters", Set.of(),
            ROOT_PACKAGE + ".inventory", Set.of());

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    static final Pattern NEO4J_NAME = Pattern.compile("\\borg\\.neo4j(?:\\.[A-Za-z_$][\\w$]*)+");
    static final Pattern COMMENTS_AND_STRINGS =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"", Pattern.DOTALL);

    @Test
    void neo4jInternalsAreReferencedOnlyFromTheAdapterPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(mainSources())) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                violations.addAll(violations(
                        Files.readString(source), source.getFileName().toString()));
            }
        }
        assertThat(violations)
                .as("Neo4j internals outside the upstream adapter")
                .isEmpty();
    }

    @Test
    void theScannerFlagsAnInternalTypeOutsideTheAdapter() {
        String source = """
                package org.dozerdb.metrics.collectors;
                // a comment naming org.neo4j.kernel.Ignored is fine
                import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;
                class X { String s = "org.neo4j.in.a.string.IsFine"; }
                """;

        assertThat(violations(source, "X.java"))
                .containsExactly(
                        "X.java (org.dozerdb.metrics.collectors): org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats");
    }

    @Test
    void theScannerAcceptsAllowedClassesAndTheirMembers() {
        String source = """
                package org.dozerdb.metrics.settings;
                import static org.neo4j.configuration.SettingValueParsers.BOOL;
                import org.neo4j.graphdb.config.Setting;
                class X { Object o = org.neo4j.configuration.GraphDatabaseSettings.neo4j_home; }
                """;

        assertThat(violations(source, "X.java")).isEmpty();
    }

    static List<String> violations(String source, String fileName) {
        Matcher packageMatcher = PACKAGE.matcher(source);
        if (!packageMatcher.find()) {
            throw new IllegalArgumentException(fileName + " has no package declaration");
        }
        String pkg = packageMatcher.group(1);
        if (pkg.equals(ADAPTER_PACKAGE)) {
            return List.of();
        }
        Set<String> allowed = ALLOWED.get(pkg);
        if (allowed == null) {
            throw new IllegalArgumentException(
                    fileName + " is in package " + pkg + ", which the allowlist does not know");
        }
        List<String> violations = new ArrayList<>();
        Matcher names = NEO4J_NAME.matcher(COMMENTS_AND_STRINGS.matcher(source).replaceAll(" "));
        while (names.find()) {
            String name = names.group();
            if (allowed.stream().noneMatch(cls -> name.equals(cls) || name.startsWith(cls + "."))) {
                violations.add(fileName + " (" + pkg + "): " + name);
            }
        }
        return violations;
    }

    static Path mainSources() {
        for (Path candidate : List.of(Path.of("src/main/java"), Path.of("metrics/src/main/java"))) {
            if (Files.isDirectory(candidate.resolve("org/dozerdb/metrics"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find the metrics module's main sources from "
                + Path.of("").toAbsolutePath());
    }
}
