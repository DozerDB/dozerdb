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
 * The field map of the JSON layout, one documented shape per test: the keys of Neo4j's query log JSON template as the
 * Operations Manual 2026.10 lists them for {@code type: query}, each present exactly when the entry carries the field.
 * Every example line of the field inventory is checked against the inventory's patterns too, by
 * {@code FieldInventoryTest}.
 */
class FieldMapRendererTest {

    private static final String BOLT_SOURCE =
            "bolt-session\tbolt\tneo4j-javascript/6.0.1\t\tclient/127.0.0.1:53864\tserver/127.0.0.1:7687>";

    private static QueryEntry.Builder tomHanksOnBolt() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("vendor", "neo4j");
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
                .queryText("MATCH (n:Person {name:'Tom Hanks'}) RETURN n")
                .parameters("{}")
                .runtime("pipelined")
                .annotationData(annotations)
                .queryLanguage("CYPHER 25")
                .executionPlanCacheKeyHash("4F2A1B3C");
    }

    @Test
    void aVerboseSuccessEntryMapsEveryDocumentedKeyWithItsJsonType() {
        Map<String, Object> fields = FieldMapRenderer.render(tomHanksOnBolt().build(), AnnotationDataFormat.CYPHER);

        assertThat(fields)
                .containsEntry("type", "query")
                .containsEntry("event", "success")
                .containsEntry("id", "34")
                .containsEntry("transactionId", 6L)
                .containsEntry("elapsedTimeMs", 195L)
                .containsEntry("planning", 185L)
                .containsEntry("waiting", 0L)
                .containsEntry("allocatedBytes", 328L)
                .containsEntry("pageHits", 68L)
                .containsEntry("pageFaults", 0L)
                .containsEntry("source", BOLT_SOURCE)
                .containsEntry("database", "neo4j")
                .containsEntry("executingUser", "neo4j")
                .containsEntry("authenticatedUser", "neo4j")
                .containsEntry("query", "MATCH (n:Person {name:'Tom Hanks'}) RETURN n")
                .containsEntry("queryParameters", "{}")
                .containsEntry("runtime", "pipelined")
                .containsEntry("annotationData", "{app: 'query', vendor: 'neo4j'}")
                .containsEntry("queryLang", "CYPHER 25")
                .containsEntry("executionPlanCacheKeyHash", "4F2A1B3C")
                .doesNotContainKeys("cpu", "queryPlan", "failureReason", "errorInfo", "stacktrace", "time", "level")
                .hasSize(20);
    }

    @Test
    void aStartEntryAtInfoHasNoIdAndAnAbsentFieldIsAbsentNotNull() {
        QueryEntry entry = tomHanksOnBolt()
                .kind(Kind.START)
                .detailLevel(DetailLevel.INFO)
                .runtime(null)
                .parameters(null)
                .queryLanguage(null)
                .executionPlanCacheKeyHash(QueryEntry.UNPLANNED_CACHE_KEY_HASH)
                .build();

        Map<String, Object> fields = FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER);

        assertThat(fields)
                .containsEntry("event", "start")
                .containsEntry("executionPlanCacheKeyHash", "00000000")
                .doesNotContainKeys("id", "runtime", "queryParameters", "queryLang", "cpu")
                .doesNotContainValue(null);
    }

    @Test
    void cpuIsPresentOnlyWhenTheEntryCarriesACpuFigure() {
        assertThat(FieldMapRenderer.render(tomHanksOnBolt().build(), AnnotationDataFormat.CYPHER))
                .doesNotContainKey("cpu");
        assertThat(FieldMapRenderer.render(tomHanksOnBolt().cpuMillis(12L).build(), AnnotationDataFormat.CYPHER))
                .containsEntry("cpu", 12L);
    }

    @Test
    void aFailedQueryEntryCarriesTheErrorInfoTreeBesideTheFailureReason() {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("column", 18L);
        position.put("offset", 17L);
        position.put("line", 1L);
        Map<String, Object> cause = new LinkedHashMap<>();
        cause.put("GQLSTATUS", "22N01");
        cause.put("statusDescription", "error: data exception - invalid type.");
        Map<String, Object> errorInfo = new LinkedHashMap<>();
        errorInfo.put("GQLSTATUS", "42N62");
        errorInfo.put("statusDescription", "error: syntax error or access rule violation - variable not defined.");
        errorInfo.put("classification", "CLIENT_ERROR");
        errorInfo.put("position", position);
        errorInfo.put("cause", cause);
        QueryEntry entry = tomHanksOnBolt()
                .kind(Kind.FAIL)
                .runtime(null)
                .failureReason("Variable `m` not defined")
                .errorInfo(errorInfo)
                .failureCause(new IllegalStateException("not part of the map"))
                .build();

        Map<String, Object> fields = FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER);

        assertThat(fields)
                .containsEntry("event", "fail")
                .containsEntry("failureReason", "Variable `m` not defined")
                .containsEntry("errorInfo", errorInfo)
                .doesNotContainKeys("runtime", "stacktrace", "failureCause");
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) fields.get("errorInfo");
        assertThat(tree.keySet())
                .containsExactly("GQLSTATUS", "statusDescription", "classification", "position", "cause");
        assertThat(tree.get("cause")).isEqualTo(cause);
    }

    @Test
    void aFailureWithoutAReasonOrAGqlStatusObjectHasNeitherKey() {
        QueryEntry entry = tomHanksOnBolt().kind(Kind.FAIL).build();

        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER))
                .containsEntry("event", "fail")
                .doesNotContainKeys("failureReason", "errorInfo");
    }

    @Test
    void aSuccessEntryNeverCarriesAFailureReason() {
        QueryEntry entry = tomHanksOnBolt().failureReason("left over").build();

        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER)).doesNotContainKey("failureReason");
    }

    @Test
    void annotationDataFollowsTheConfiguredFormat() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("k", 1L);
        nested.put("flag", true);
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("foo", "bar");
        annotations.put("baz", nested);
        annotations.put("list", List.of("a", "b"));
        annotations.put("missing", null);
        QueryEntry entry = tomHanksOnBolt().annotationData(annotations).build();

        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER))
                .containsEntry(
                        "annotationData", "{baz: {flag: true, k: 1}, foo: 'bar', list: ['a', 'b'], missing: <null>}");

        Map<String, Object> expectedJson = new LinkedHashMap<>();
        Map<String, Object> expectedNested = new LinkedHashMap<>();
        expectedNested.put("flag", true);
        expectedNested.put("k", 1L);
        expectedJson.put("baz", expectedNested);
        expectedJson.put("foo", "bar");
        expectedJson.put("list", List.of("a", "b"));
        expectedJson.put("missing", null);
        Object json = FieldMapRenderer.render(entry, AnnotationDataFormat.JSON).get("annotationData");
        assertThat(json).isEqualTo(expectedJson);
        assertThat(keysOf(json)).containsExactly("baz", "foo", "list", "missing");

        Map<String, Object> expectedFlat = new LinkedHashMap<>();
        expectedFlat.put("baz.flag", true);
        expectedFlat.put("baz.k", 1L);
        expectedFlat.put("foo", "bar");
        expectedFlat.put("list", List.of("a", "b"));
        expectedFlat.put("missing", null);
        Object flat =
                FieldMapRenderer.render(entry, AnnotationDataFormat.FLAT_JSON).get("annotationData");
        assertThat(flat).isEqualTo(expectedFlat);
        assertThat(keysOf(flat)).containsExactly("baz.flag", "baz.k", "foo", "list", "missing");
    }

    @Test
    void emptyAnnotationDataIsAnEmptyMapOrTheEmptyCypherMap() {
        QueryEntry entry = tomHanksOnBolt().annotationData(Map.of()).build();

        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER)).containsEntry("annotationData", "{}");
        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.JSON)).containsEntry("annotationData", Map.of());
        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.FLAT_JSON))
                .containsEntry("annotationData", Map.of());
    }

    @Test
    void theJsonFormatsLeaveThePlainLineAsTheCypherMap() {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("baz", Map.of("k", 1L));
        QueryEntry entry = tomHanksOnBolt().annotationData(annotations).build();

        assertThat(PlainTextRenderer.render(entry)).endsWith(" - {baz: {k: 1}}");
    }

    @Test
    void theQueryPlanIsOneStringWhenTheEntryCarriesOne() {
        QueryEntry entry = tomHanksOnBolt()
                .queryPlan("+---------+\n| Operator |\n+---------+")
                .build();

        assertThat(FieldMapRenderer.render(entry, AnnotationDataFormat.CYPHER))
                .containsEntry("queryPlan", "+---------+\n| Operator |\n+---------+");
    }

    private static List<String> keysOf(Object map) {
        return ((Map<?, ?>) map).keySet().stream().map(String::valueOf).toList();
    }
}
