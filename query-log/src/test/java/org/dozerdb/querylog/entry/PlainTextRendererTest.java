/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dozerdb.querylog.entry.QueryEntry.Kind;
import org.junit.jupiter.api.Test;

/**
 * The plain-text grammar, one DozerDB-defined shape per test: the manual's newest documented end entry (the 2026.10
 * "more details" example, tabs restored where the manual's HTML expanded them) and the shapes DozerDB defines where
 * the manual is silent. Every example line of the field inventory is rendered too, by {@link FieldInventoryTest}.
 */
class PlainTextRendererTest {

    /** The manual's Bolt source segment: {@code bolt-session\tbolt\t<driver>\t\tclient/<ip>:<port>\tserver/<ip>:<port>>}. */
    static final String BOLT_SOURCE =
            "bolt-session\tbolt\tneo4j-javascript/6.0.1\t\tclient/127.0.0.1:53864\tserver/127.0.0.1:7687>";

    /** Community's HTTP source segment: {@code server-session\t<scheme>\t<client host>\t<request path>}. */
    static final String HTTP_SOURCE = "server-session\thttp\t127.0.0.1\t/db/neo4j/tx/45/commit";

    /** Community's embedded source segment, which already ends with a tab. */
    static final String EMBEDDED_SOURCE = "embedded-session\t";

    private static final String TOM_HANKS =
            "MATCH (n:Person {name:'Tom Hanks'})-[:ACTED_IN]->(n1:Movie)<-[:DIRECTED]-(n2:Person {name:\"Tom Hanks\"}) RETURN n1.title";

    private static final String ZEROED = "0 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults";

    private static QueryEntry.Builder tomHanksOnBolt() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("vendor", "neo4j");
        annotations.put("type", "user-direct");
        annotations.put("container_app", "workspace");
        annotations.put("app", "query");
        return QueryEntry.builder()
                .queryId(34)
                .transactionId(6)
                .elapsedMillis(195)
                .planningMillis(185)
                .waitingMillis(0)
                .allocatedBytes(328)
                .pageHits(68)
                .pageFaults(0)
                .source(BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .authenticatedUser("neo4j")
                .queryText(TOM_HANKS)
                .parameters("{}")
                .runtime("pipelined")
                .annotationData(annotations)
                .executionPlanCacheKeyHash("1A2B3C4D");
    }

    @Test
    void theVerboseSuccessEntryMatchesTheManualsExampleLine() {
        assertThat(PlainTextRenderer.render(tomHanksOnBolt().build()))
                .isEqualTo("id:34 - transaction id:6 - 195 ms: (planning: 185, waiting: 0) - 328 B"
                        + " - 68 page hits, 0 page faults - " + BOLT_SOURCE + "\tneo4j - neo4j - " + TOM_HANKS
                        + " - {} - runtime=pipelined"
                        + " - {app: 'query', container_app: 'workspace', type: 'user-direct', vendor: 'neo4j'}");
    }

    @Test
    void theInfoEndEntryHasNoQueryIdPrefix() {
        QueryEntry entry = tomHanksOnBolt().detailLevel(DetailLevel.INFO).build();

        assertThat(PlainTextRenderer.render(entry))
                .startsWith("transaction id:6 - 195 ms: (planning: 185, waiting: 0)");
    }

    @Test
    void theStartEntryIsTheEndGrammarWithZeroedValuesAndNoRuntime() {
        QueryEntry entry = QueryEntry.builder()
                .kind(Kind.START)
                .queryId(34)
                .transactionId(12)
                .source(BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .queryText("MATCH (n:Person) RETURN n  LIMIT 5")
                .parameters("{}")
                .executionPlanCacheKeyHash(QueryEntry.UNPLANNED_CACHE_KEY_HASH)
                .build();

        assertThat(PlainTextRenderer.render(entry))
                .isEqualTo("Query started: id:34 - transaction id:12 - " + ZEROED + " - " + BOLT_SOURCE
                        + "\tneo4j - neo4j - MATCH (n:Person) RETURN n  LIMIT 5 - {} - runtime=null - {}");
    }

    @Test
    void theFailedQueryEntryAppendsTheReasonAfterTheAnnotations() {
        QueryEntry entry = QueryEntry.builder()
                .kind(Kind.FAIL)
                .queryId(7)
                .transactionId(12)
                .elapsedMillis(3)
                .planningMillis(1)
                .source(BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .queryText("MATCH (n) RETURN m")
                .parameters("{}")
                .failureReason("Variable `m` not defined")
                .failureCause(new IllegalStateException("Variable `m` not defined"))
                .build();

        assertThat(entry.failed()).isTrue();
        assertThat(PlainTextRenderer.render(entry))
                .isEqualTo(
                        "id:7 - transaction id:12 - 3 ms: (planning: 1, waiting: 0) - 0 B - 0 page hits, 0 page faults - "
                                + BOLT_SOURCE + "\tneo4j - neo4j - MATCH (n) RETURN m - {} - runtime=null - {}"
                                + " - Variable `m` not defined");
    }

    @Test
    void aFailureWithoutAReasonEndsWithTheAnnotations() {
        QueryEntry entry = QueryEntry.builder()
                .kind(Kind.FAIL)
                .source(BOLT_SOURCE)
                .database("neo4j")
                .executingUser("neo4j")
                .queryText("RETURN 1")
                .parameters("{}")
                .build();

        assertThat(PlainTextRenderer.render(entry)).endsWith(" - RETURN 1 - {} - runtime=null - {}");
    }

    @Test
    void theHttpSourceIsFollowedByTheDatabaseAfterOneTab() {
        QueryEntry entry = tomHanksOnBolt().source(HTTP_SOURCE).build();

        assertThat(PlainTextRenderer.render(entry)).contains(" - " + HTTP_SOURCE + "\tneo4j - neo4j - MATCH");
    }

    @Test
    void theEmbeddedSourceKeepsItsTrailingTabSoTwoTabsPrecedeTheDatabase() {
        QueryEntry entry = tomHanksOnBolt().source(EMBEDDED_SOURCE).build();

        assertThat(PlainTextRenderer.render(entry)).contains(" - embedded-session\t\tneo4j - neo4j - MATCH");
    }

    @Test
    void aQueryThatNeverReachedADatabaseOrARuntimeWritesNoneAndRuntimeNull() {
        QueryEntry entry = QueryEntry.builder()
                .kind(Kind.FAIL)
                .queryId(7)
                .elapsedMillis(2)
                .planningMillis(2)
                .source(BOLT_SOURCE)
                .database(QueryEntry.NO_DATABASE)
                .executingUser("neo4j")
                .queryText("RETURN 1 (")
                .parameters("{}")
                .failureReason("Invalid input '(': expected an expression")
                .build();

        assertThat(PlainTextRenderer.render(entry))
                .isEqualTo(
                        "id:7 - transaction id:-1 - 2 ms: (planning: 2, waiting: 0) - 0 B - 0 page hits, 0 page faults - "
                                + BOLT_SOURCE + "\t<none> - neo4j - RETURN 1 ( - {} - runtime=null - {}"
                                + " - Invalid input '(': expected an expression");
    }

    @Test
    void absentParametersDropTheirSegmentRatherThanWritingNull() {
        QueryEntry entry = tomHanksOnBolt().parameters(null).build();

        assertThat(PlainTextRenderer.render(entry))
                .contains(" - " + TOM_HANKS + " - runtime=pipelined - {app:")
                .doesNotContain("null");
    }

    @Test
    void anAbsentUserLeavesItsSegmentEmptyRatherThanWritingNull() {
        QueryEntry entry = tomHanksOnBolt().executingUser(null).build();

        assertThat(PlainTextRenderer.render(entry))
                .contains("\tneo4j -  - " + TOM_HANKS)
                .doesNotContain("null");
    }

    @Test
    void theQueryTextIsVerbatimWithItsLineBreaks() {
        QueryEntry entry = tomHanksOnBolt()
                .queryText("\nMATCH (n:Person) RETURN n  LIMIT 5;")
                .build();

        assertThat(PlainTextRenderer.render(entry))
                .contains("\tneo4j - neo4j - \nMATCH (n:Person) RETURN n  LIMIT 5; - {} - runtime=pipelined - {");
    }

    @Test
    void thePlanTableFollowsTheEntryOnItsOwnLines() {
        String table = "+-----------------+----+\n| Operator        | Id |\n+-----------------+----+";
        QueryEntry entry = tomHanksOnBolt().queryPlan(table).build();

        assertThat(PlainTextRenderer.render(entry))
                .endsWith(" - {app: 'query', container_app: 'workspace', type: 'user-direct', vendor: 'neo4j'}\n"
                        + table);
    }

    @Test
    void anEntryWithoutAPlanIsOneLine() {
        assertThat(PlainTextRenderer.render(tomHanksOnBolt().build())).doesNotContain("\n");
    }

    @Test
    void annotationValuesRenderAsACypherMapWithSortedKeysAndNestedValues() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("retries", 3L);
        annotations.put("nested", Map.of("flag", true));
        annotations.put("tags", List.of("a", "b"));
        annotations.put("missing", null);
        annotations.put("ratio", 0.5);
        QueryEntry entry = tomHanksOnBolt().annotationData(annotations).build();

        assertThat(PlainTextRenderer.render(entry))
                .endsWith(" - {missing: <null>, nested: {flag: true}, ratio: 0.5, retries: 3, tags: ['a', 'b']}");
    }
}
