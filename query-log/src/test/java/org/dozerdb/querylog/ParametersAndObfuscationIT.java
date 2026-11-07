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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.dozerdb.querylog.inventory.ReleaseLines;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;

/**
 * The four detail flags that shape the text, the parameters and the failure of an entry reach the plain file and
 * change at runtime, each set on the kernel's live configuration (the way {@code dbms.setConfigValue} sets it):
 * {@code db.logs.query.max_parameter_length} cuts each value inside the value, {@code parameter_logging_enabled=false}
 * drops the segment, {@code obfuscate_literals} replaces the literals of a query's text once its cached compilation is
 * cleared (the manual's {@code CALL db.clearQueryCaches()}), a password is masked at every setting, and
 * {@code obfuscate_errors} writes the failed-query entry with the obfuscated status description and no stack trace.
 * Every test restores the default it changed, so the tests run in any order on one server.
 */
class ParametersAndObfuscationIT {

    private static final String PARAMETERISED_QUERY = "MATCH (p:Person {name: $name}) RETURN p.born + $n";
    private static final Map<String, Object> PARAMETERS = Map.of("name", "Alexander the Great", "n", 42);

    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServer() {
        neo4j = QueryLogServers.startWithBolt();
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
        queryLog = QueryLogFile.of(neo4j);
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
    void parametersAreTheSingleQuotedCypherMapAndTheMaximumLengthCutsEachValueInsideTheValue() {
        String query = PARAMETERISED_QUERY + " // full";
        run(query, PARAMETERS);
        assertThat(queryLog.awaitEntryContaining(query))
                .contains(" - " + query + " - {name: 'Alexander the Great', n: 42} - runtime=");

        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.query_log_max_parameter_length, 10);
        try {
            String cut = PARAMETERISED_QUERY + " // cut";
            run(cut, PARAMETERS);
            assertThat(queryLog.awaitEntryContaining(cut))
                    .contains(" - " + cut + " - {name: 'Alexander ...', n: 42} - runtime=");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.query_log_max_parameter_length, Integer.MAX_VALUE);
        }
    }

    @Test
    void withParameterLoggingOffTheSegmentIsDroppedSeparatorIncluded() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_parameter_logging_enabled, false);
        try {
            String query = PARAMETERISED_QUERY + " // no parameters";
            run(query, PARAMETERS);
            queryLog.awaitEntryContaining(query);
            List<String> entries = queryLog.entriesContaining(query);
            assertThat(entries).as("a start entry and an end entry").hasSize(2);
            assertThat(entries)
                    .allSatisfy(entry -> assertThat(entry)
                            .contains(" - " + query + " - runtime=")
                            .doesNotContain("Alexander")
                            .doesNotContain(" - {} - runtime="));
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_parameter_logging_enabled, true);
        }
    }

    @Test
    void obfuscateLiteralsAppliesToACachedEmbeddedQueryOnlyOnceTheQueryCachesAreCleared() {
        GraphDatabaseService database = neo4j.databaseManagementService().database("neo4j");
        String literal = "literal-" + UUID.randomUUID();
        String query = "RETURN '" + literal + "' AS text, $p AS parameter";
        Map<String, Object> parameters = Map.of("p", "parameter value stays");
        database.executeTransactionally(query, parameters);
        queryLog.awaitEntryContaining(query);
        assertThat(queryLog.endEntriesContaining(query)).hasSize(1);

        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, true);
        try {
            // The compiled query carries the obfuscation it was planned with, so the setting changes nothing yet.
            database.executeTransactionally(query, parameters);
            awaitEmbeddedSentinel(database);
            assertThat(queryLog.endEntriesContaining(query))
                    .as("the cached query still shows its literal")
                    .hasSize(2);

            database.executeTransactionally("CALL db.clearQueryCaches()");
            database.executeTransactionally(query, parameters);
            String obfuscated = "RETURN ****** AS text, $p AS parameter";
            String entry = queryLog.awaitEntryContaining(" - " + obfuscated + " - ");
            assertThat(entry)
                    .as("literals are placeholders, parameters are not (the manual: use parameter_logging_enabled)")
                    .contains(" - " + obfuscated + " - {p: 'parameter value stays'} - runtime=")
                    .doesNotContain(literal);
            assertThat(queryLog.endEntriesContaining(query))
                    .as("no further entry shows the literal")
                    .hasSize(2);
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, false);
            database.executeTransactionally("CALL db.clearQueryCaches()");
        }
    }

    @Test
    void obfuscateLiteralsReachesTheNextBoltQueryWithoutACacheClearOnTheCalendarLineAndAfterOneOnLts() {
        // Neo4j 2026.10 collects both the all-literals and the sensitive-only views at parse time and picks between
        // them with the live setting on the routed (Bolt, HTTP) path, so no cache clear is needed there. Neo4j 5.26
        // decides at parse time and caches the decision with the routed query, so there the documented cache clear
        // comes first.
        String literal = "bolt-literal-" + UUID.randomUUID();
        String query = "RETURN '" + literal + "' AS text";
        run(query, Map.of());
        queryLog.awaitEntryContaining(query);

        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, true);
        try {
            if (ReleaseLines.current() == ReleaseLines.LTS) {
                run("CALL db.clearQueryCaches()", Map.of());
            }
            run(query, Map.of());
            awaitBoltSentinel();
            assertThat(queryLog.endEntriesContaining("RETURN ****** AS text"))
                    .as("the second run is obfuscated")
                    .isNotEmpty();
            assertThat(queryLog.endEntriesContaining(query))
                    .as("only the first run shows the literal")
                    .hasSize(1);
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, false);
        }
    }

    @Test
    void aPasswordInAUserManagementStatementIsMaskedAtEverySetting() {
        String password = "topsecret-" + UUID.randomUUID();
        String literalStatement = "CREATE USER alice SET PASSWORD '" + password + "' CHANGE NOT REQUIRED";
        runOnSystem(literalStatement, Map.of());
        String masked = queryLog.awaitEntryContaining("CREATE USER alice SET PASSWORD ");
        assertThat(masked).contains("CREATE USER alice SET PASSWORD ****** CHANGE NOT REQUIRED");

        String parameterStatement = "CREATE USER bob SET PASSWORD $pw CHANGE NOT REQUIRED";
        runOnSystem(parameterStatement, Map.of("pw", password));
        assertThat(queryLog.awaitEntryContaining(parameterStatement)).contains(" - {pw: '******'} - ");

        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, true);
        try {
            runOnSystem("CREATE USER carol SET PASSWORD '" + password + "' CHANGE NOT REQUIRED", Map.of());
            // The user name is a literal too, so obfuscate_literals masks it with the password.
            queryLog.awaitEntryContaining("CREATE USER ****** SET PASSWORD ****** CHANGE NOT REQUIRED");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_literals, false);
        }
        String sentinel = runSentinel();
        assertThat(queryLog.entriesContaining(password))
                .as("the password never reaches the file; sentinel %s", sentinel)
                .isEmpty();
    }

    @Test
    void underObfuscateErrorsTheFailedQueryEntryCarriesTheObfuscatedDescriptionAndNoStackTrace() {
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_errors, true);
        try {
            String query = "MATCH (n) RETURN obfuscatedErrorVariable";
            try (Session session = driver.session()) {
                assertThatThrownBy(() -> session.run(query).consume()).isInstanceOf(ClientException.class);
            }
            String entry = queryLog.awaitEntryContaining(query);
            // Neo4j 5.26 raises "Variable not defined" without a GQL status of its own: the router reports the default
            // status, whose standard description stands in.
            String description = RouterRaisedError.onTheRunningLine()
                    ? RouterRaisedError.standardDescription()
                    : "error: syntax error or access rule violation - invalid syntax";
            assertThat(entry)
                    .as("one line, no stack trace, the obfuscated description last: %s", entry)
                    .doesNotContain("\n")
                    .matches(Pattern.compile("^\\S+ \\S+ ERROR id:\\d+ - .* - " + Pattern.quote(query)
                            + " - \\{\\} - runtime=null - \\{\\} - " + Pattern.quote(description) + "$"));
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_obfuscate_errors, false);
        }
    }

    private static void run(String query, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            session.run(query, parameters).consume();
        }
    }

    private static void runOnSystem(String query, Map<String, Object> parameters) {
        try (Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.run(query, parameters).consume();
        }
    }

    private static String runSentinel() {
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel, Map.of());
        queryLog.awaitEntryContaining(sentinel);
        return sentinel;
    }

    /** A sentinel for a server obfuscating literals: its {@code 1} is masked too, so the wait is on its alias. */
    private static String aliasSentinel() {
        return "sentinel_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void awaitBoltSentinel() {
        String alias = aliasSentinel();
        run("RETURN 1 AS " + alias, Map.of());
        queryLog.awaitEntryContaining(alias);
    }

    private static void awaitEmbeddedSentinel(GraphDatabaseService database) {
        String alias = aliasSentinel();
        database.executeTransactionally("RETURN 1 AS " + alias);
        queryLog.awaitEntryContaining(alias);
    }
}
