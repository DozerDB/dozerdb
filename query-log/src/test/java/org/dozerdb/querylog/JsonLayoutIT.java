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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dozerdb.querylog.inventory.FieldInventory;
import org.dozerdb.querylog.inventory.ReleaseLines;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListenerAdapter;
import org.neo4j.harness.Neo4j;

/**
 * The manual's JSON switch: a {@code server-logs.xml} whose {@code QueryLog} appender uses Neo4j's
 * {@code QueryLogJsonLayout.json} template turns {@code query.log} into one JSON object per line with Neo4j's field
 * names, and nothing in DozerDB has to change for it. The file written here is the test's own minimal configuration
 * (two appenders, two loggers), never a copy of Neo4j's, and {@code server.logs.config} points at it. Every field of a
 * start, success and failed-query entry is checked against the field inventory's rows and patterns, and so is every
 * key of a transaction's start, commit and failed rollback entries.
 */
class JsonLayoutIT {

    /** The keys the layout adds from the log event; every other key comes from the entry's field map. */
    private static final Set<String> LAYOUT_KEYS = Set.of("time", "level", "stacktrace");

    private static final List<String> ALWAYS_PRESENT = List.of(
            "type",
            "event",
            "id",
            "transactionId",
            "elapsedTimeMs",
            "planning",
            "waiting",
            "allocatedBytes",
            "pageHits",
            "pageFaults",
            "source",
            "database",
            "executingUser",
            "authenticatedUser",
            "query",
            "queryParameters",
            "annotationData",
            "executionPlanCacheKeyHash",
            "queryLang");

    private static final FieldInventory INVENTORY = FieldInventory.load();

    /** The release line of the running kernel, whose statuses and defaults of the inventory apply. */
    private static final ReleaseLines LINE = ReleaseLines.current();

    /** Whether the line can carry the query language at all: Neo4j 5.26's snapshot has no accessor for it. */
    private static final boolean QUERY_LANG_WRITTEN =
            !INVENTORY.tier("queryLang", LINE).equals("not-applicable");

    /** What the commit-refusing listener throws; the rollback's reason in the entry. */
    private static final String REFUSAL = "commit refused on purpose, in json";

    /** Transactions that create a node with this label are refused at commit by the test's listener. */
    private static final Label POISON = Label.label("JsonPoison");

    private static Path home;
    private static Neo4j neo4j;
    private static Driver driver;
    private static QueryLogFile queryLog;

    @BeforeAll
    static void startServerWithTheJsonLayout() throws IOException {
        home = Files.createTempDirectory("dozerdb-query-log-json");
        Path xml = home.resolve("server-logs.xml");
        Files.writeString(xml, minimalServerLogsXml(), StandardCharsets.UTF_8);
        neo4j = QueryLogServers.startWithBolt(
                builder -> builder.withConfig(GraphDatabaseSettings.neo4j_home, home.toAbsolutePath())
                        .withConfig(GraphDatabaseSettings.server_logging_config_path, xml.toAbsolutePath()));
        neo4j.databaseManagementService().registerTransactionEventListener("neo4j", new CommitRefusingListener());
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

    /**
     * The smallest logging configuration that routes the query log through Neo4j's JSON template: the server's own
     * logging keeps a plain debug.log so that nothing else changes.
     */
    static String minimalServerLogsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Configuration status="ERROR">
                    <Appenders>
                        <File name="DebugLog" fileName="${config:server.directories.logs}/debug.log">
                            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n"/>
                        </File>
                        <File name="QueryLog" fileName="${config:server.directories.logs}/query.log">
                            <JsonTemplateLayout eventTemplateUri="classpath:org/neo4j/logging/QueryLogJsonLayout.json"/>
                        </File>
                    </Appenders>
                    <Loggers>
                        <Root level="INFO">
                            <AppenderRef ref="DebugLog"/>
                        </Root>
                        <Logger name="QueryLogger" level="INFO" additivity="false">
                            <AppenderRef ref="QueryLog"/>
                        </Logger>
                    </Loggers>
                </Configuration>
                """;
    }

    @Test
    void aSuccessfulQueryWritesAStartAndASuccessEntryAsOneJsonObjectEachWithTheInventorysFields() {
        String query = "RETURN 'json success' AS text";
        run(query);

        JsonNode end = queryLog.awaitJsonEntryWhere(
                "event=success and query=" + query, entry -> isEntryOf(entry, "success", query));
        JsonNode start = queryLog.awaitJsonEntryWhere(
                "event=start and query=" + query, entry -> isEntryOf(entry, "start", query));

        assertThat(queryLog.jsonEntriesWhere(
                        entry -> entry.path("query").asText().equals(query)))
                .as("one start entry and one end entry")
                .hasSize(2);
        assertMatchesInventory(start, "start", "INFO");
        assertMatchesInventory(end, "success", "INFO");
        assertThat(end.get("type").asText()).isEqualTo("query");
        assertThat(end.get("id").asText()).isEqualTo(start.get("id").asText());
        assertThat(end.get("transactionId").isIntegralNumber()).isTrue();
        assertThat(end.get("elapsedTimeMs").isIntegralNumber()).isTrue();
        assertThat(end.get("database").asText()).isEqualTo("neo4j");
        assertThat(end.get("runtime").asText()).isNotEqualTo("null");
        if (QUERY_LANG_WRITTEN) {
            assertThat(end.get("queryLang").asText()).startsWith("CYPHER ");
        } else {
            assertThat(end.has("queryLang"))
                    .as("no queryLang on this line: %s", end)
                    .isFalse();
        }
        assertThat(end.get("executionPlanCacheKeyHash").asText()).matches("[0-9A-F]{8}");
        // Nothing sets db.logs.query.annotation_data_format here, so the entry shows Neo4j's default of this line:
        // the Cypher-map string under CYPHER (5.26), an object under JSON (the calendar line).
        JsonNode annotationData = end.get("annotationData");
        String defaultFormat = INVENTORY.settingDefault("db.logs.query.annotation_data_format", LINE);
        assertThat(defaultFormat).isIn("CYPHER", "JSON");
        if (defaultFormat.equals("CYPHER")) {
            assertThat(annotationData.isTextual()).as("%s", end).isTrue();
            assertThat(annotationData.asText()).isEqualTo("{}");
        } else {
            assertThat(annotationData.isObject()).as("%s", end).isTrue();
            assertThat(annotationData.toString()).isEqualTo("{}");
        }
        assertThat(end.has("stacktrace")).isFalse();
        assertThat(start.has("runtime"))
                .as("a start entry has no runtime, so the key is absent")
                .isFalse();
        assertThat(start.get("executionPlanCacheKeyHash").asText()).isEqualTo("00000000");
    }

    @Test
    void aFailedQueryWritesOneErrorEntryWithTheErrorInfoTreeTheFailureReasonAndTheStackTrace() {
        String query = "MATCH (n) RETURN m";
        assertThatThrownBy(() -> run(query)).isInstanceOf(ClientException.class);
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel);
        queryLog.awaitJsonEntryWhere("the sentinel", entry -> isEntryOf(entry, "success", sentinel));

        List<JsonNode> failed = queryLog.jsonEntriesWhere(entry -> isEntryOf(entry, "fail", query));
        assertThat(failed).as("exactly one failed-query entry").hasSize(1);
        JsonNode entry = failed.get(0);
        assertMatchesInventory(entry, "fail", "ERROR");
        assertThat(entry.get("failureReason").asText()).contains("Variable `m` not defined");
        JsonNode errorInfo = entry.get("errorInfo");
        assertThat(errorInfo.isObject())
                .as("errorInfo is the GQLSTATUS tree: %s", entry)
                .isTrue();
        if (INVENTORY.tier("errorInfo", LINE).equals("partial")) {
            // errorInfo is Partial where Neo4j raises "Variable not defined" without a GQL status of its own (5.26):
            // the router reports the default status, with no position and no cause. The positioned-status test below
            // shows the tree of a failure that has one.
            JsonNode shown = INVENTORY.jsonEntryOf("router-raised-error");
            assertThat(errorInfo.get("GQLSTATUS"))
                    .isEqualTo(shown.get("errorInfo").get("GQLSTATUS"));
            assertThat(errorInfo.has("position")).as("%s", errorInfo).isFalse();
            assertThat(errorInfo.has("cause")).as("%s", errorInfo).isFalse();
            // The compatibility page shows this entry as the shape of a router-raised error; its line is what the
            // layout writes, apart from the time, the ids, the timings and the source.
            for (String key : List.of("level", "type", "event", "database", "query", "failureReason", "errorInfo")) {
                assertThat(entry.get(key)).as("%s of %s", key, entry).isEqualTo(shown.get(key));
            }
        } else {
            // Neo4j reports the syntax error (42001) with the undefined variable (42N62) as its cause; the manual's
            // example shows the inner object alone. The tree is written as Neo4j nests it.
            assertThat(errorInfo.get("GQLSTATUS").asText()).isEqualTo("42001");
            assertThat(errorInfo.get("classification").asText()).isEqualTo("CLIENT_ERROR");
            assertThat(errorInfo.get("position").get("column").asInt()).isEqualTo(18);
            assertThat(errorInfo.get("position").get("offset").asInt()).isEqualTo(17);
            assertThat(errorInfo.get("position").get("line").asInt()).isEqualTo(1);
            JsonNode cause = errorInfo.get("cause");
            assertThat(cause.get("GQLSTATUS").asText()).isEqualTo("42N62");
            assertThat(cause.get("statusDescription").asText())
                    .contains("variable not defined. Variable `m` not defined.");
            assertThat(cause.get("classification").asText()).isEqualTo("CLIENT_ERROR");
            assertThat(cause.has("cause")).isFalse();
        }
        assertThat(entry.get("stacktrace").asText()).startsWith("org.neo4j.exceptions.SyntaxException");
    }

    @Test
    void aFailureWithAPositionedStatusCarriesThePositionAtEveryLevelOfTheErrorInfoTree() {
        // An unaliased WITH item is reported with a GQL status and its position on both release lines.
        String query = "WITH 1 RETURN 1 AS positioned";
        assertThatThrownBy(() -> run(query)).isInstanceOf(ClientException.class);
        String sentinel = QueryLogFile.sentinelQuery();
        run(sentinel);
        queryLog.awaitJsonEntryWhere("the sentinel", entry -> isEntryOf(entry, "success", sentinel));

        List<JsonNode> failed = queryLog.jsonEntriesWhere(entry -> isEntryOf(entry, "fail", query));
        assertThat(failed).as("exactly one failed-query entry").hasSize(1);
        JsonNode entry = failed.get(0);
        assertMatchesInventory(entry, "fail", "ERROR");
        assertThat(entry.get("failureReason").asText()).contains("Expression in WITH must be aliased (use AS)");
        JsonNode errorInfo = entry.get("errorInfo");
        assertThat(errorInfo.get("GQLSTATUS").asText()).as("%s", entry).isEqualTo("42001");
        assertThat(errorInfo.get("classification").asText()).isEqualTo("CLIENT_ERROR");
        assertThat(errorInfo.get("position").get("column").asInt()).isEqualTo(6);
        assertThat(errorInfo.get("position").get("offset").asInt()).isEqualTo(5);
        assertThat(errorInfo.get("position").get("line").asInt()).isEqualTo(1);
        JsonNode cause = errorInfo.get("cause");
        assertThat(cause.get("GQLSTATUS").asText()).isEqualTo("42N21");
        assertThat(cause.get("position").get("offset").asInt()).isEqualTo(5);
        assertThat(cause.has("cause")).isFalse();
    }

    @Test
    void theLayoutCutsAStringOverSixteenKibAndTheEntryStaysValidJson() {
        String literal = "x".repeat(17_000);
        String query = "RETURN '" + literal + "' AS big";
        run(query);
        String head = query.substring(0, 100);

        JsonNode end = queryLog.awaitJsonEntryWhere(
                "event=success and a 17000-character query",
                entry -> entry.path("event").asText().equals("success")
                        && entry.path("query").asText().startsWith(head));

        String logged = end.get("query").asText();
        assertThat(logged).hasSize(16 * 1024 + 1).endsWith("…");
        assertThat(end.get("elapsedTimeMs").isIntegralNumber()).isTrue();
    }

    @Test
    void cpuAppearsOnceNeo4jTracksTheCpuTime() {
        String untracked = "RETURN 'cpu untracked' AS text";
        run(untracked);
        JsonNode before = queryLog.awaitJsonEntryWhere(
                "the untracked end entry", entry -> isEntryOf(entry, "success", untracked));
        assertThat(before.has("cpu")).isFalse();

        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.track_query_cpu_time, true);
        try {
            String tracked = "RETURN 'cpu tracked' AS text";
            run(tracked);
            JsonNode after = queryLog.awaitJsonEntryWhere(
                    "the tracked end entry", entry -> isEntryOf(entry, "success", tracked));
            assertThat(after.get("cpu").isIntegralNumber())
                    .as("cpu is a number: %s", after)
                    .isTrue();
            assertMatchesInventory(after, "success", "INFO");
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.track_query_cpu_time, false);
        }
    }

    @Test
    void annotationDataFollowsTheAnnotationDataFormatSetting() {
        TransactionConfig metadata = TransactionConfig.builder()
                .withMetadata(Map.of("app", "json-layout", "nested", Map.of("k", 1L)))
                .build();
        // The calendar line's default is JSON (5.26's is CYPHER), so every format is set on purpose.
        GraphDatabaseSettings.AnnotationDataFormat configured =
                neo4j.config().get(GraphDatabaseSettings.log_queries_annotation_data_format);
        try {
            QueryLogServers.setDynamic(
                    neo4j,
                    GraphDatabaseSettings.log_queries_annotation_data_format,
                    GraphDatabaseSettings.AnnotationDataFormat.CYPHER);
            String cypher = "RETURN 'annotations as cypher' AS text";
            run(cypher, metadata);
            JsonNode asCypher =
                    queryLog.awaitJsonEntryWhere("cypher annotations", entry -> isEntryOf(entry, "success", cypher));
            assertThat(asCypher.get("annotationData").isTextual())
                    .as("%s", asCypher)
                    .isTrue();
            assertThat(asCypher.get("annotationData").asText()).isEqualTo("{app: 'json-layout', nested: {k: 1}}");
            QueryLogServers.setDynamic(
                    neo4j,
                    GraphDatabaseSettings.log_queries_annotation_data_format,
                    GraphDatabaseSettings.AnnotationDataFormat.JSON);
            String json = "RETURN 'annotations as json' AS text";
            run(json, metadata);
            JsonNode asJson =
                    queryLog.awaitJsonEntryWhere("json annotations", entry -> isEntryOf(entry, "success", json));
            assertThat(asJson.get("annotationData").isObject()).as("%s", asJson).isTrue();
            assertThat(asJson.get("annotationData").get("app").asText()).isEqualTo("json-layout");
            assertThat(asJson.get("annotationData").get("nested").get("k").asInt())
                    .isEqualTo(1);

            QueryLogServers.setDynamic(
                    neo4j,
                    GraphDatabaseSettings.log_queries_annotation_data_format,
                    GraphDatabaseSettings.AnnotationDataFormat.FLAT_JSON);
            String flat = "RETURN 'annotations as flat json' AS text";
            run(flat, metadata);
            JsonNode asFlat =
                    queryLog.awaitJsonEntryWhere("flat annotations", entry -> isEntryOf(entry, "success", flat));
            assertThat(asFlat.get("annotationData").isObject()).as("%s", asFlat).isTrue();
            assertThat(asFlat.get("annotationData").get("app").asText()).isEqualTo("json-layout");
            assertThat(asFlat.get("annotationData").get("nested.k").asInt()).isEqualTo(1);
            assertThat(asFlat.get("annotationData").has("nested")).isFalse();
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_annotation_data_format, configured);
        }
    }

    @Test
    void aTransactionWritesOneJsonObjectPerEventWithTheManualsTransactionKeysAndTheQuerysTransactionId() {
        QueryLogServers.setDynamic(
                neo4j,
                GraphDatabaseSettings.log_queries_transactions_level,
                GraphDatabaseSettings.LogQueryLevel.VERBOSE);
        try {
            String query = "RETURN 'inside a logged transaction' AS text";
            try (Session session = driver.session();
                    org.neo4j.driver.Transaction tx = session.beginTransaction()) {
                tx.run(query).consume();
                tx.commit();
            }

            JsonNode queryEntry = queryLog.awaitJsonEntryWhere(
                    "the query's success entry", entry -> isEntryOf(entry, "success", query));
            long transactionId = queryEntry.get("transactionId").asLong();
            JsonNode commit = queryLog.awaitJsonEntryWhere(
                    "the commit entry of transaction " + transactionId,
                    entry -> isTransactionEntryOf(entry, "commit", transactionId));
            JsonNode start = queryLog.awaitJsonEntryWhere(
                    "the start entry of transaction " + transactionId,
                    entry -> isTransactionEntryOf(entry, "start", transactionId));

            for (JsonNode entry : List.of(start, commit)) {
                List<String> keys = new ArrayList<>();
                entry.fieldNames().forEachRemaining(keys::add);
                assertThat(keys)
                        .as("the layout's two keys, then the manual's transaction keys in sorted order: %s", entry)
                        .containsExactly(
                                "time",
                                "level",
                                "authenticatedUser",
                                "database",
                                "event",
                                "executingUser",
                                "transactionId",
                                "type");
                assertThat(entry.get("level").asText()).isEqualTo("INFO");
                assertThat(entry.get("type").asText()).isEqualTo("transaction");
                assertThat(entry.get("database").asText()).isEqualTo("neo4j");
                for (String key : keys) {
                    JsonNode row = transactionRowFor(key);
                    assertThat(entry.get(key).asText())
                            .as("%s matches the inventory pattern of %s", entry.get(key), key)
                            .matches(row.get("pattern").asText());
                }
            }
        } finally {
            QueryLogServers.setDynamic(
                    neo4j,
                    GraphDatabaseSettings.log_queries_transactions_level,
                    GraphDatabaseSettings.LogQueryLevel.OFF);
        }
    }

    @Test
    void aRollbackCausedByAFailureCarriesTheReasonUnderFailureReasonAndTheStackTraceAtError() {
        QueryLogServers.setDynamic(
                neo4j,
                GraphDatabaseSettings.log_queries_transactions_level,
                GraphDatabaseSettings.LogQueryLevel.VERBOSE);
        try {
            String query = "CREATE (:JsonPoison {marker: 'refused in json'})";
            assertThatThrownBy(() -> {
                        try (Session session = driver.session();
                                org.neo4j.driver.Transaction tx = session.beginTransaction()) {
                            tx.run(query).consume();
                            tx.commit();
                        }
                    })
                    .hasMessageContaining(REFUSAL);

            JsonNode queryEntry = queryLog.awaitJsonEntryWhere(
                    "the query's success entry", entry -> isEntryOf(entry, "success", query));
            long transactionId = queryEntry.get("transactionId").asLong();
            JsonNode rollback = queryLog.awaitJsonEntryWhere(
                    "the rollback entry of transaction " + transactionId,
                    entry -> isTransactionEntryOf(entry, "rollback", transactionId));

            List<String> keys = new ArrayList<>();
            rollback.fieldNames().forEachRemaining(keys::add);
            assertThat(keys)
                    .as("the manual's keys plus failureReason, then the layout's stacktrace: %s", rollback)
                    .containsExactly(
                            "time",
                            "level",
                            "authenticatedUser",
                            "database",
                            "event",
                            "executingUser",
                            "failureReason",
                            "transactionId",
                            "type",
                            "stacktrace");
            assertThat(rollback.get("level").asText()).isEqualTo("ERROR");
            assertThat(rollback.get("failureReason").asText()).isEqualTo(REFUSAL);
            assertThat(rollback.get("stacktrace").asText()).startsWith("java.lang.IllegalStateException: " + REFUSAL);
            for (String key : keys) {
                JsonNode row = transactionRowFor(key);
                assertThat(contains(row.get("entries"), "rollback"))
                        .as("%s is written on a rollback entry", key)
                        .isTrue();
                assertThat(rollback.get(key).asText())
                        .as("%s matches the inventory pattern of %s", rollback.get(key), key)
                        .matches(row.get("pattern").asText());
            }
        } finally {
            QueryLogServers.setDynamic(
                    neo4j,
                    GraphDatabaseSettings.log_queries_transactions_level,
                    GraphDatabaseSettings.LogQueryLevel.OFF);
        }
    }

    /** Refuses the commit of any transaction that created a {@code JsonPoison} node, so Neo4j rolls it back with a failure. */
    private static final class CommitRefusingListener extends TransactionEventListenerAdapter<Object> {
        @Override
        public Object beforeCommit(TransactionData data, org.neo4j.graphdb.Transaction tx, GraphDatabaseService db) {
            for (Node node : data.createdNodes()) {
                if (node.hasLabel(POISON)) {
                    throw new IllegalStateException(REFUSAL);
                }
            }
            return null;
        }
    }

    private static boolean isTransactionEntryOf(JsonNode entry, String event, long transactionId) {
        return entry.path("type").asText().equals("transaction")
                && entry.path("event").asText().equals(event)
                && entry.path("transactionId").asLong() == transactionId;
    }

    /** The inventory row of a transaction entry's JSON key: a transaction row or a common one, never a query row. */
    private static JsonNode transactionRowFor(String key) {
        return INVENTORY.fields().stream()
                .filter(row -> !row.get("jsonKey").isNull()
                        && row.get("jsonKey").asText().equals(key))
                .filter(row -> !row.get("type").asText().equals("query"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the inventory has no transaction JSON row '" + key + "'"));
    }

    @Test
    void thePlanIsOneStringFieldOnceThePlanDescriptionSettingIsOn() {
        String query = "MATCH (n) RETURN n AS withJsonPlan";
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            run(query);
            JsonNode end = queryLog.awaitJsonEntryWhere(
                    "event=success and query=" + query, entry -> isEntryOf(entry, "success", query));

            assertMatchesInventory(end, "success", "INFO");
            JsonNode plan = end.get("queryPlan");
            assertThat(plan.isTextual()).as("queryPlan is one string: %s", end).isTrue();
            assertThat(plan.asText())
                    .contains("\n+-")
                    .containsPattern("\\| Operator\\s+\\| Id\\s+\\| Details\\s+\\| Estimated Rows\\s+\\|")
                    .contains("+AllNodesScan");
            JsonNode start = queryLog.awaitJsonEntryWhere(
                    "event=start and query=" + query, entry -> isEntryOf(entry, "start", query));
            assertThat(start.has("queryPlan"))
                    .as("a start entry carries no plan")
                    .isFalse();
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
        String without = "MATCH (n) RETURN n AS withoutJsonPlan";
        run(without);
        JsonNode end = queryLog.awaitJsonEntryWhere(
                "event=success and query=" + without, entry -> isEntryOf(entry, "success", without));
        assertThat(end.has("queryPlan"))
                .as("the key is absent once the setting is off again")
                .isFalse();
    }

    @Test
    void aPlanOverSixteenKibIsCutByTheLayoutLikeAnyStringAndTheEntryStaysValidJson() {
        // A union of many branches: every branch is a row of the table, so the table outgrows the layout's limit.
        StringBuilder query = new StringBuilder("RETURN 0 AS bigPlanBranch");
        for (int i = 1; i < 120; i++) {
            query.append(" UNION ALL RETURN ").append(i).append(" AS bigPlanBranch");
        }
        QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, true);
        try {
            run(query.toString());
            JsonNode end = queryLog.awaitJsonEntryWhere(
                    "event=success and the union query", entry -> isEntryOf(entry, "success", query.toString()));

            String plan = end.get("queryPlan").asText();
            assertThat(plan).hasSize(16 * 1024 + 1).endsWith("…");
            assertThat(end.get("elapsedTimeMs").isIntegralNumber()).isTrue();
        } finally {
            QueryLogServers.setDynamic(neo4j, GraphDatabaseSettings.log_queries_query_plan, false);
        }
    }

    private static boolean isEntryOf(JsonNode entry, String event, String query) {
        return entry.path("event").asText().equals(event)
                && entry.path("query").asText().equals(query);
    }

    private static void run(String query) {
        run(query, TransactionConfig.empty());
    }

    private static void run(String query, TransactionConfig config) {
        try (Session session = driver.session()) {
            session.run(query, config).consume();
        }
    }

    /**
     * Every key of the entry is one the inventory documents for this kind at the default {@code VERBOSE} level, every
     * value matches its row's pattern, the always-present keys are there, no value is {@code null}, and the field map's
     * keys come out in sorted order between the layout's {@code time} and {@code level} and its {@code stacktrace}.
     */
    private static void assertMatchesInventory(JsonNode entry, String kind, String level) {
        List<String> keys = new ArrayList<>();
        entry.fieldNames().forEachRemaining(keys::add);
        List<String> alwaysPresent = ALWAYS_PRESENT.stream()
                .filter(key -> !INVENTORY.tier(key, LINE).equals("not-applicable"))
                .toList();
        assertThat(keys).as("entry %s", entry).startsWith("time", "level").containsAll(alwaysPresent);
        List<String> mapKeys =
                keys.stream().filter(key -> !LAYOUT_KEYS.contains(key)).toList();
        assertThat(mapKeys).as("field map keys are sorted: %s", entry).isSorted();
        if (keys.contains("stacktrace")) {
            assertThat(keys.get(keys.size() - 1)).isEqualTo("stacktrace");
        }
        assertThat(entry.get("level").asText()).isEqualTo(level);
        assertThat(entry.get("event").asText()).isEqualTo(kind);
        for (String key : keys) {
            JsonNode value = entry.get(key);
            assertThat(value.isNull()).as("%s is never null: %s", key, entry).isFalse();
            JsonNode row = rowFor(key, kind);
            JsonNode pattern = row.get("pattern");
            if (pattern.isNull()) {
                assertThat(value.isObject())
                        .as("%s is object-valued: %s", key, entry)
                        .isTrue();
            } else {
                // annotationData is an object under the JSON formats and a string under CYPHER; both read as text.
                String text = value.isObject() ? value.toString() : value.asText();
                assertThat(text)
                        .as("%s matches the inventory pattern of %s", value, key)
                        .matches(pattern.asText());
            }
        }
    }

    private static JsonNode rowFor(String key, String kind) {
        return INVENTORY.fields().stream()
                .filter(row -> !row.get("jsonKey").isNull()
                        && row.get("jsonKey").asText().equals(key))
                .filter(row -> !row.get("type").asText().equals("transaction"))
                .filter(row -> contains(row.get("entries"), kind) && contains(row.get("levels"), "VERBOSE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the inventory has no JSON row '" + key + "' for a " + kind + " entry at VERBOSE"));
    }

    private static boolean contains(JsonNode array, String word) {
        for (JsonNode element : array) {
            if (element.asText().equals(word)) {
                return true;
            }
        }
        return false;
    }
}
