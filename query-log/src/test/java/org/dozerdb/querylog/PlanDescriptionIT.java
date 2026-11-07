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

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;

/**
 * {@code db.logs.query.plan_description_enabled} attaches the plan table to the end entry: off by default, so an end
 * entry is one line; once set at runtime, the end entry of a query that compiled is followed by the table Neo4j's
 * {@code EXPLAIN} shows, in which every operator is a row and the header names {@code Operator}, {@code Id},
 * {@code Details} and {@code Estimated Rows}. The table comes from the query's plan description supplier, which only a
 * successful compile sets and which never carries profile figures, so a query that fails to compile has no table and a
 * {@code PROFILE} query's table has no {@code Rows} or {@code DB Hits}. The setting is dynamic, as the Operations
 * Manual says; every test sets the value it needs and puts the default back, so the tests run in any order on one
 * server.
 */
class PlanDescriptionIT {

    /** The table's header row; the renderer pads each cell to its column's width. */
    private static final Pattern TABLE_HEADER =
            Pattern.compile("^\\| Operator\\s+\\| Id\\s+\\| Details\\s+\\| Estimated Rows\\s+\\|$");

    /** An operator row of the table: the tree-line prefix, {@code +} and the operator name. */
    private static final Pattern OPERATOR_ROW = Pattern.compile("^\\| [| ]*\\+(?<operator>\\w+)\\s+\\|\\s+\\d+ \\|.*");

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
        queryLog = QueryLogFile.of(neo4j);
        run("CREATE (:Person {name: 'plan description'})");
    }

    @AfterAll
    static void stopServer() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.close();
        }
    }

    @Test
    void byDefaultTheEndEntryIsOneLineWithoutATable() {
        assertThat(neo4j.config().get(GraphDatabaseSettings.log_queries_query_plan))
                .as("Neo4j's default for db.logs.query.plan_description_enabled")
                .isFalse();
        String query = "MATCH (n:Person) RETURN n.name AS withoutPlan";
        run(query);
        runSentinel();

        List<String> entries = queryLog.endEntriesContaining(query);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).lines())
                .as("one line and no table: %s", entries.get(0))
                .hasSize(1);
    }

    @Test
    void onceEnabledAtRuntimeTheEndEntryIsFollowedByTheOperatorTable() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            String query = "MATCH (n:Person) RETURN n.name AS withPlan";
            run(query);
            runSentinel();

            List<String> entries = queryLog.endEntriesContaining(query);
            assertThat(entries).hasSize(1);
            List<String> lines = entries.get(0).lines().toList();
            assertThat(lines.get(0))
                    .as("the entry line comes first and ends with the annotations: %s", lines.get(0))
                    .matches(".* INFO  id:\\d+ - transaction id:\\d+ - .* - " + Pattern.quote(query)
                            + " - \\{\\} - runtime=[a-z]+ - \\{\\}");
            assertThat(lines)
                    .as("the table follows on the lines after: %s", entries.get(0))
                    .anyMatch(line -> TABLE_HEADER.matcher(line).matches());
            List<String> operators = lines.stream()
                    .map(OPERATOR_ROW::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group("operator"))
                    .toList();
            assertThat(operators)
                    .as("every operator of the plan is a row: %s", entries.get(0))
                    .startsWith("ProduceResults")
                    .contains("NodeByLabelScan");
            assertThat(queryLog.startEntriesContaining(query))
                    .as("the start entry stays one line")
                    .singleElement()
                    .satisfies(start -> assertThat(start.lines()).hasSize(1));
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
    }

    @Test
    void aQueryThatFailsToCompileHasNoTable() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            String query = "MATCH (n:Person) RETURN undefinedWithPlan";
            assertThatThrownBy(() -> run(query)).isInstanceOf(ClientException.class);
            runSentinel();

            List<String> entries = queryLog.endEntriesContaining(query);
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0))
                    .as("a failed-query entry at ERROR with its stack trace and no table")
                    .contains(" ERROR id:")
                    .contains("\n\tat org.neo4j.")
                    .doesNotContain("| Operator");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
    }

    @Test
    void aQueryThatFailsWhileRunningKeepsItsTableBeforeTheStackTrace() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            String query = "MATCH (n:Person) RETURN 1 / (size(n.name) - size(n.name)) AS failsWithPlan";
            assertThatThrownBy(() -> run(query)).isInstanceOf(ClientException.class);
            runSentinel();

            List<String> entries = queryLog.endEntriesContaining(query);
            assertThat(entries).hasSize(1);
            List<String> lines = entries.get(0).lines().toList();
            assertThat(lines.get(0)).contains(" ERROR id:").contains("/ by zero");
            int header = indexOfFirst(lines, line -> TABLE_HEADER.matcher(line).matches());
            int trace = indexOfFirst(lines, line -> line.startsWith("\tat org.neo4j."));
            assertThat(header)
                    .as("the table is in the entry: %s", entries.get(0))
                    .isPositive();
            assertThat(trace)
                    .as("the stack trace follows the table: %s", entries.get(0))
                    .isGreaterThan(header);
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
    }

    @Test
    void aProfileQuerysTableHasNoProfileFigures() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            String query = "PROFILE MATCH (n:Person) RETURN count(n) AS profiledWithPlan";
            run(query);
            runSentinel();

            List<String> entries = queryLog.endEntriesContaining(query);
            assertThat(entries).hasSize(1);
            List<String> lines = entries.get(0).lines().toList();
            String header = lines.stream()
                    .filter(line -> line.startsWith("| Operator"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no table header in " + entries.get(0)));
            assertThat(header)
                    .as("the EXPLAIN shape: no Rows, DB Hits, Page Cache or Time columns")
                    .matches(TABLE_HEADER)
                    .doesNotContain("| Rows")
                    .doesNotContain("DB Hits")
                    .doesNotContain("Page Cache")
                    .doesNotContain("Time (ms)");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
    }

    private static int indexOfFirst(List<String> lines, Predicate<String> condition) {
        for (int i = 0; i < lines.size(); i++) {
            if (condition.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void run(String query) {
        try (Session session = driver.session()) {
            session.run(query).consume();
        }
    }

    /** Runs a sentinel query and waits for its entry, so that the absence of a table can be judged. */
    private static void runSentinel() {
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel);
        queryLog.awaitEntryContaining(sentinel);
    }
}
