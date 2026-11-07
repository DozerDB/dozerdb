/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Enforces the upstream adapter boundary: a Neo4j class that is not {@code @PublicApi} is named only inside
 * {@code org.dozerdb.querylog.adapter}, so that a Neo4j upgrade can break that package alone. The allow-list for the
 * other packages is empty, with no exception: the entry model and the extension lifecycle are pure Java, and the
 * module declares no settings, so nothing outside the adapter has to touch a Neo4j type.
 *
 * <p>Every {@code org.neo4j} name in the main sources counts, imported or inline, once comments and string literals
 * are blanked out; a source file in a package the table does not know fails the test, so a new package is added here
 * on purpose. The same scan keeps the adapter itself out of the other packages: the adapter's factory is the
 * composition root that hands the extension its ports, so nothing outside the adapter names an adapter class.
 */
class UpstreamAdapterBoundaryTest {

    static final String ROOT_PACKAGE = "org.dozerdb.querylog";
    static final String ADAPTER_PACKAGE = ROOT_PACKAGE + ".adapter";

    /** Neo4j classes each non-adapter package may name; the query log needs none. */
    static final Map<String, Set<String>> ALLOWED =
            Map.of(ROOT_PACKAGE + ".entry", Set.of(), ROOT_PACKAGE + ".extension", Set.of());

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    static final Pattern NEO4J_CLASS = Pattern.compile("\\borg\\.neo4j(?:\\.[A-Za-z_$][\\w$]*)+");
    static final Pattern ADAPTER_CLASS =
            Pattern.compile("\\borg\\.dozerdb\\.querylog\\.adapter(?:\\.[A-Za-z_$][\\w$]*)+");
    static final Pattern BLANKED_OUT = Pattern.compile(
            "/\\*.*?\\*/" // block comment
                    + "|//[^\\n]*" // line comment
                    + "|\"\"\".*?\"\"\"" // text block
                    + "|\"(?:\\\\.|[^\"\\\\\\n])*\"", // string literal
            Pattern.DOTALL);

    @Test
    void onlyTheAdapterPackageNamesNeo4jInternals() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                offenders.addAll(neo4jNamesOutsideTheAdapter(
                        Files.readString(file), file.getFileName().toString()));
            }
        }
        assertThat(offenders)
                .as("Neo4j classes named outside org.dozerdb.querylog.adapter")
                .isEmpty();
    }

    @Test
    void onlyTheAdapterPackageNamesTheAdapter() throws IOException {
        // The factory in the adapter is the composition root Neo4j calls: it hands the extension its ports, so the
        // extension and the entry model depend on nothing in the adapter and a Neo4j upgrade reaches them through
        // the ports alone.
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                offenders.addAll(adapterNamesOutsideTheAdapter(
                        Files.readString(file), file.getFileName().toString()));
            }
        }
        assertThat(offenders)
                .as("adapter classes named outside org.dozerdb.querylog.adapter")
                .isEmpty();
    }

    @Test
    void theScanFlagsAnAdapterImportOutsideTheAdapterAndNotInsideIt() {
        String outside =
                """
                package org.dozerdb.querylog.extension;
                import org.dozerdb.querylog.adapter.ConfigurationAdapter;
                class X {}
                """;
        String inside =
                """
                package org.dozerdb.querylog.adapter;
                import org.dozerdb.querylog.adapter.ConfigurationAdapter;
                class X {}
                """;

        assertThat(adapterNamesOutsideTheAdapter(outside, "X.java"))
                .containsExactly(
                        "X.java (org.dozerdb.querylog.extension): org.dozerdb.querylog.adapter.ConfigurationAdapter");
        assertThat(adapterNamesOutsideTheAdapter(inside, "X.java")).isEmpty();
    }

    @Test
    void theScanFlagsAnImportAndAnInlineNameButNotCommentsOrStrings() {
        String source =
                """
                package org.dozerdb.querylog.entry;
                /* org.neo4j.kernel.InABlockComment */
                // org.neo4j.kernel.InALineComment
                import org.neo4j.kernel.api.query.ExecutingQuery;
                class X {
                    String s = "org.neo4j.kernel.InAString";
                    Object o = new org.neo4j.values.utils.PrettyPrinter();
                }
                """;

        assertThat(neo4jNamesOutsideTheAdapter(source, "X.java"))
                .containsExactly(
                        "X.java (org.dozerdb.querylog.entry): org.neo4j.kernel.api.query.ExecutingQuery",
                        "X.java (org.dozerdb.querylog.entry): org.neo4j.values.utils.PrettyPrinter");
    }

    @Test
    void theScanIgnoresTheAdapterPackage() {
        String source =
                """
                package org.dozerdb.querylog.adapter;
                import org.neo4j.kernel.api.query.ExecutingQuery;
                class X {}
                """;

        assertThat(neo4jNamesOutsideTheAdapter(source, "X.java")).isEmpty();
    }

    @Test
    void aPackageTheTableDoesNotKnowIsRejected() {
        String source =
                """
                package org.dozerdb.querylog.policy;
                class X {}
                """;

        assertThatThrownBy(() -> neo4jNamesOutsideTheAdapter(source, "X.java"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.dozerdb.querylog.policy");
    }

    static List<String> neo4jNamesOutsideTheAdapter(String source, String fileName) {
        Matcher declaration = PACKAGE_DECLARATION.matcher(source);
        if (!declaration.find()) {
            throw new IllegalStateException(fileName + " declares no package");
        }
        String pkg = declaration.group(1);
        if (pkg.equals(ADAPTER_PACKAGE)) {
            return List.of();
        }
        Set<String> allowed = ALLOWED.get(pkg);
        if (allowed == null) {
            throw new IllegalStateException(
                    fileName + " is in " + pkg + ", a package the boundary table does not know");
        }
        List<String> offenders = new ArrayList<>();
        Matcher names = NEO4J_CLASS.matcher(BLANKED_OUT.matcher(source).replaceAll(" "));
        while (names.find()) {
            String name = names.group();
            boolean permitted = allowed.stream().anyMatch(cls -> name.equals(cls) || name.startsWith(cls + "."));
            if (!permitted) {
                offenders.add(fileName + " (" + pkg + "): " + name);
            }
        }
        return offenders;
    }

    static List<String> adapterNamesOutsideTheAdapter(String source, String fileName) {
        Matcher declaration = PACKAGE_DECLARATION.matcher(source);
        if (!declaration.find()) {
            throw new IllegalStateException(fileName + " declares no package");
        }
        String pkg = declaration.group(1);
        if (pkg.equals(ADAPTER_PACKAGE)) {
            return List.of();
        }
        List<String> offenders = new ArrayList<>();
        Matcher names = ADAPTER_CLASS.matcher(BLANKED_OUT.matcher(source).replaceAll(" "));
        while (names.find()) {
            offenders.add(fileName + " (" + pkg + "): " + names.group());
        }
        return offenders;
    }

    /** Surefire runs from the module directory, an IDE may run from the repository root. */
    static Path mainSources() {
        for (Path candidate : List.of(Path.of("src/main/java"), Path.of("query-log/src/main/java"))) {
            if (Files.isDirectory(candidate.resolve("org/dozerdb/querylog"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No query-log main sources under " + Path.of("").toAbsolutePath());
    }
}
