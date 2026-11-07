/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.FieldMapRenderer;
import org.dozerdb.querylog.entry.PlainTextRenderer;
import org.dozerdb.querylog.entry.TransactionFieldMapRenderer;
import org.dozerdb.querylog.entry.TransactionPlainTextRenderer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The field inventory is the one record of every query log setting, field and DozerDB-defined shape: this test
 * validates its shape, and feeds every example line through the plain renderer from the record the inventory pairs
 * with it, byte for byte, so the inventory can never show a line DozerDB does not write.
 */
class FieldInventoryTest {

    private static final FieldInventory INVENTORY = FieldInventory.load();

    /** The JSON keys Neo4j's layout takes from the log event rather than from the field map. */
    private static final Set<String> LAYOUT_KEYS = Set.of("time", "level", "stacktrace");

    /** The JSON keys every query entry carries whatever its record says: constants of the kind and the metadata map. */
    private static final Set<String> ALWAYS_PRESENT = Set.of("type", "event", "annotationData");

    /** The JSON keys every transaction entry carries whatever its record says. */
    private static final Set<String> TRANSACTION_ALWAYS_PRESENT = Set.of("type", "event", "transactionId");

    /** The plain marker that opens a transaction entry of each kind. */
    private static final Map<String, String> TRANSACTION_MARKERS = Map.of(
            "start", "Transaction started: ",
            "commit", "Transaction committed: ",
            "rollback", "Transaction rolled back: ");

    /** JSON key to the key of the example record it is read from, for the keys a record may leave out. */
    private static final Map<String, String> RECORD_KEY_OF = Map.ofEntries(
            Map.entry("id", "queryId"),
            Map.entry("transactionId", "transactionId"),
            Map.entry("elapsedTimeMs", "elapsedMillis"),
            Map.entry("planning", "planningMillis"),
            Map.entry("cpu", "cpuMillis"),
            Map.entry("waiting", "waitingMillis"),
            Map.entry("allocatedBytes", "allocatedBytes"),
            Map.entry("pageHits", "pageHits"),
            Map.entry("pageFaults", "pageFaults"),
            Map.entry("source", "source"),
            Map.entry("database", "database"),
            Map.entry("executingUser", "executingUser"),
            Map.entry("authenticatedUser", "authenticatedUser"),
            Map.entry("query", "queryText"),
            Map.entry("queryParameters", "parameters"),
            Map.entry("runtime", "runtime"),
            Map.entry("queryLang", "queryLanguage"),
            Map.entry("executionPlanCacheKeyHash", "executionPlanCacheKeyHash"),
            Map.entry("queryPlan", "queryPlan"),
            Map.entry("failureReason", "failureReason"),
            Map.entry("errorInfo", "errorInfo"));

    /** JSON key of a transaction row to the key of the example record it is read from. */
    private static final Map<String, String> TRANSACTION_RECORD_KEY_OF = Map.of(
            "transactionId", "transactionId",
            "database", "database",
            "executingUser", "executingUser",
            "authenticatedUser", "authenticatedUser",
            "failureReason", "failureReason");

    @Test
    void theCommittedInventoryValidatesAndCarriesBothReleaseLines() {
        assertThat(INVENTORY.lines()).containsExactlyInAnyOrder("calendar", "lts");
        assertThat(INVENTORY.fields()).isNotEmpty();
        assertThat(INVENTORY.examples()).isNotEmpty();
    }

    @TestFactory
    List<DynamicTest> everyExampleLineIsRenderedByteForByteFromItsRecord() {
        return INVENTORY.examples().stream()
                .map(example -> DynamicTest.dynamicTest(example.get("id").asText(), () -> {
                    boolean error = example.get("level").asText().equals("ERROR");
                    if (FieldInventory.isTransaction(example)) {
                        var entry = FieldInventory.transactionRecord(example);
                        assertThat(TransactionPlainTextRenderer.render(entry)).isEqualTo(FieldInventory.line(example));
                        assertThat(entry.failed())
                                .as("a rollback caused by a failure is at ERROR, every other transaction entry at INFO")
                                .isEqualTo(error);
                        return;
                    }
                    var entry = FieldInventory.record(example);
                    assertThat(PlainTextRenderer.render(entry)).isEqualTo(FieldInventory.line(example));
                    assertThat(entry.failed())
                            .as("a failed-query entry is at ERROR, every other entry at INFO")
                            .isEqualTo(error);
                }))
                .toList();
    }

    @Test
    void everyPartialRowIsNamedByAShapeThatShowsAnExampleLine() {
        // The compatibility page lists every shape DozerDB defines with an example line; a Partial row is a shape the
        // manual leaves open, so each one must be named by a shape that carries at least one line.
        Set<String> namedByAShapeWithALine = new LinkedHashSet<>();
        for (JsonNode shape : INVENTORY.shapes()) {
            if (!shape.get("examples").isEmpty() || !shape.get("lines").isEmpty()) {
                elementsOf(shape.get("rows")).forEach(row -> namedByAShapeWithALine.add(row.asText()));
            }
        }
        for (JsonNode row : INVENTORY.fields()) {
            for (String line : INVENTORY.lines()) {
                if (row.get("status").get(line).get("tier").asText().equals("partial")) {
                    assertThat(namedByAShapeWithALine)
                            .as(
                                    "Partial row %s on the %s line is shown by a shape",
                                    row.get("name").asText(), line)
                            .contains(row.get("name").asText());
                }
            }
        }
    }

    @Test
    void theSettingsAreTheElevenDocumentedOnesAndTheOneDeprecatedUnsupportedRow() {
        List<String> names = INVENTORY.settings().stream()
                .map(setting -> setting.get("name").asText())
                .toList();
        assertThat(names)
                .hasSize(12)
                .allMatch(name -> name.startsWith("db.logs.query."))
                .contains("db.logs.query.enabled", "db.logs.query.annotation_data_as_json_enabled");
        for (JsonNode setting : INVENTORY.settings()) {
            for (String line : INVENTORY.lines()) {
                String tier = setting.get("status").get(line).get("tier").asText();
                assertThat(tier.equals("unsupported"))
                        .as(
                                "%s on %s is the one Unsupported row",
                                setting.get("name").asText(), line)
                        .isEqualTo(
                                setting.get("name").asText().equals("db.logs.query.annotation_data_as_json_enabled"));
            }
        }
    }

    @Test
    void aSettingRowNeedsItsDefaultPerLineWhetherItIsDynamicAStatusPerLineAndNotes() {
        assertThatThrownBy(() ->
                        validated(root -> ((ObjectNode) root.get("settings").get(0)).remove("dynamic")))
                .hasMessageContaining("setting 'db.logs.query.enabled' has 'dynamic'");
        assertThatThrownBy(() -> validated(
                        root -> ((ObjectNode) root.get("settings").get(0).get("default")).remove("lts")))
                .hasMessageContaining("setting 'db.logs.query.enabled': 'default.lts' is a string or null");
        assertThatThrownBy(() -> validated(root ->
                        ((ObjectNode) root.get("settings").get(0).get("status").get("lts")).put("tier", "supported")))
                .hasMessageContaining("setting 'db.logs.query.enabled': 'status.lts.tier' is one of");
        assertThatThrownBy(() ->
                        validated(root -> ((ObjectNode) root.get("settings").get(0)).put("extra", 1)))
                .hasMessageContaining("setting 'db.logs.query.enabled': key 'extra' is not part of the shape");
        assertThatThrownBy(() -> validated(
                        root -> ((ObjectNode) root.get("settings").get(1)).put("name", "db.logs.query.enabled")))
                .hasMessageContaining("setting 'db.logs.query.enabled' is named once");
    }

    @Test
    void aShapeNamesExistingRowsAndExamplesAndCarriesItsTitleAndText() {
        assertThatThrownBy(() ->
                        validated(root -> ((ArrayNode) root.get("shapes").get(0).get("rows")).add("nope")))
                .hasMessageContaining("shape 'start-entry': row 'nope' exists");
        assertThatThrownBy(() ->
                        validated(root -> ((ArrayNode) root.get("shapes").get(0).get("examples")).add("nope")))
                .hasMessageContaining("shape 'start-entry': example 'nope' exists");
        assertThatThrownBy(
                        () -> validated(root -> ((ObjectNode) root.get("shapes").get(0)).remove("text")))
                .hasMessageContaining("shape 'start-entry' has 'text'");
        assertThatThrownBy(
                        () -> validated(root -> ((ObjectNode) root.get("shapes").get(1)).put("id", "start-entry")))
                .hasMessageContaining("shape 'start-entry' is identified once");
    }

    private static FieldInventory validated(Consumer<JsonNode> change) {
        JsonNode copy = INVENTORY.root().deepCopy();
        change.accept(copy);
        return FieldInventory.of(copy);
    }

    @Test
    void everyExampleAFieldNamesExistsAndEveryExampleIsNamedByAField() {
        List<String> named = INVENTORY.fields().stream()
                .flatMap(field -> elementsOf(field.get("examples")).stream())
                .map(JsonNode::asText)
                .distinct()
                .toList();
        List<String> ids = INVENTORY.examples().stream()
                .map(example -> example.get("id").asText())
                .toList();
        assertThat(named).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void everyPlainSegmentOfAnExampleLineAppearsInIt() {
        // The event marker and the id: prefix are the segments whose presence depends on the kind and the level.
        for (JsonNode example : INVENTORY.examples()) {
            String line = FieldInventory.line(example);
            if (FieldInventory.isTransaction(example)) {
                assertThat(line)
                        .as("a transaction entry opens with its kind's marker")
                        .startsWith(TRANSACTION_MARKERS.get(example.get("kind").asText()));
                continue;
            }
            boolean verbose = example.get("detailLevel").asText().equals("VERBOSE");
            assertThat(line.startsWith("Query started: "))
                    .isEqualTo(example.get("kind").asText().equals("start"));
            assertThat(line.replaceFirst("^Query started: ", "").startsWith("id:"))
                    .isEqualTo(verbose);
        }
    }

    @TestFactory
    List<DynamicTest> everyExampleFieldMapHasExactlyTheInventorysJsonKeysForItsKindAndLevelAndEveryValueMatches() {
        return INVENTORY.examples().stream()
                .filter(example -> !FieldInventory.isTransaction(example))
                .map(example -> DynamicTest.dynamicTest(example.get("id").asText(), () -> {
                    Map<String, Object> fields =
                            FieldMapRenderer.render(FieldInventory.record(example), AnnotationDataFormat.CYPHER);
                    String kind = example.get("kind").asText();
                    String level = example.get("detailLevel").asText();
                    JsonNode record = example.get("record");

                    Set<String> expected = new LinkedHashSet<>();
                    for (JsonNode row : queryJsonRows()) {
                        String key = row.get("jsonKey").asText();
                        boolean applicable = contains(row.get("levels"), level) && contains(row.get("entries"), kind);
                        boolean carried = ALWAYS_PRESENT.contains(key) || record.hasNonNull(RECORD_KEY_OF.get(key));
                        if (applicable && carried) {
                            expected.add(key);
                        }
                    }
                    assertThat(fields.keySet())
                            .as(
                                    "the field map of %s has a key exactly for each applicable row whose value the record carries",
                                    example.get("id").asText())
                            .containsExactlyInAnyOrderElementsOf(expected);

                    for (Map.Entry<String, Object> field : fields.entrySet()) {
                        JsonNode row = rowByJsonKey(field.getKey());
                        JsonNode pattern = row.get("pattern");
                        if (pattern.isNull()) {
                            assertThat(field.getValue())
                                    .as("%s is object-valued", field.getKey())
                                    .isInstanceOf(Map.class);
                        } else {
                            assertThat(String.valueOf(field.getValue()))
                                    .as("%s matches the inventory pattern of %s", field.getValue(), field.getKey())
                                    .matches(pattern.asText());
                        }
                    }
                }))
                .toList();
    }

    @TestFactory
    List<DynamicTest> everyTransactionExampleFieldMapHasExactlyTheInventorysTransactionKeysAndEveryValueMatches() {
        return INVENTORY.examples().stream()
                .filter(FieldInventory::isTransaction)
                .map(example -> DynamicTest.dynamicTest(example.get("id").asText(), () -> {
                    Map<String, Object> fields =
                            TransactionFieldMapRenderer.render(FieldInventory.transactionRecord(example));
                    String kind = example.get("kind").asText();
                    String level = example.get("detailLevel").asText();
                    JsonNode record = example.get("record");

                    Set<String> expected = new LinkedHashSet<>(TRANSACTION_ALWAYS_PRESENT);
                    for (JsonNode row : transactionJsonRows()) {
                        String key = row.get("jsonKey").asText();
                        boolean applicable = contains(row.get("levels"), level) && contains(row.get("entries"), kind);
                        if (applicable && record.hasNonNull(TRANSACTION_RECORD_KEY_OF.get(key))) {
                            expected.add(key);
                        }
                    }
                    assertThat(fields.keySet())
                            .as(
                                    "the field map of %s has a key exactly for each applicable transaction row whose value the record carries",
                                    example.get("id").asText())
                            .containsExactlyInAnyOrderElementsOf(expected);

                    for (Map.Entry<String, Object> field : fields.entrySet()) {
                        JsonNode row = transactionRowByJsonKey(field.getKey());
                        assertThat(String.valueOf(field.getValue()))
                                .as("%s matches the inventory pattern of %s", field.getValue(), field.getKey())
                                .matches(row.get("pattern").asText());
                    }
                }))
                .toList();
    }

    @Test
    void aTransactionRowIsPendingExactlyWhenNoExampleShowsIt() {
        Set<String> shownKeys = new LinkedHashSet<>();
        for (JsonNode example : INVENTORY.examples()) {
            if (FieldInventory.isTransaction(example)) {
                shownKeys.addAll(TransactionFieldMapRenderer.render(FieldInventory.transactionRecord(example))
                        .keySet());
            }
        }
        for (JsonNode row : INVENTORY.fields()) {
            if (!row.get("type").asText().equals("transaction")) {
                continue;
            }
            boolean pending = row.get("provenance").asText().equals("pending");
            boolean shown = row.get("jsonKey").isNull()
                    ? !row.get("examples").isEmpty()
                    : shownKeys.contains(row.get("jsonKey").asText());
            assertThat(pending)
                    .as(
                            "transaction row %s is pending while an example shows it, or shown by no example while not pending",
                            row.get("name").asText())
                    .isEqualTo(!shown);
        }
    }

    @Test
    void aJsonRowIsPendingExactlyWhenNoExampleFieldMapShowsIt() {
        Set<String> shown = new LinkedHashSet<>();
        for (JsonNode example : INVENTORY.examples()) {
            if (FieldInventory.isTransaction(example)) {
                continue;
            }
            shown.addAll(FieldMapRenderer.render(FieldInventory.record(example), AnnotationDataFormat.CYPHER)
                    .keySet());
        }
        for (JsonNode row : queryJsonRows()) {
            String key = row.get("jsonKey").asText();
            boolean pending = row.get("provenance").asText().equals("pending");
            assertThat(pending)
                    .as(
                            "row %s is pending while an example shows its JSON key, or shown by no example while not pending",
                            key)
                    .isEqualTo(!shown.contains(key));
        }
    }

    /** The rows of a query entry's field map: a JSON key, not a transaction row, not one the layout writes itself. */
    private static List<JsonNode> queryJsonRows() {
        return INVENTORY.fields().stream()
                .filter(row -> !row.get("jsonKey").isNull())
                .filter(row -> !row.get("type").asText().equals("transaction"))
                .filter(row -> !LAYOUT_KEYS.contains(row.get("jsonKey").asText()))
                .toList();
    }

    /** The rows of a transaction entry's field map: the transaction rows with a JSON key and the common rows. */
    private static List<JsonNode> transactionJsonRows() {
        return INVENTORY.fields().stream()
                .filter(row -> !row.get("jsonKey").isNull())
                .filter(row -> !row.get("type").asText().equals("query"))
                .filter(row -> !LAYOUT_KEYS.contains(row.get("jsonKey").asText()))
                .toList();
    }

    private static JsonNode transactionRowByJsonKey(String key) {
        return transactionJsonRows().stream()
                .filter(row -> row.get("jsonKey").asText().equals(key))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("the transaction field map has a key the inventory does not know: " + key));
    }

    private static JsonNode rowByJsonKey(String key) {
        return queryJsonRows().stream()
                .filter(row -> row.get("jsonKey").asText().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the field map has a key the inventory does not know: " + key));
    }

    private static boolean contains(JsonNode array, String word) {
        for (JsonNode element : array) {
            if (element.asText().equals(word)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRowMissingAKeyIsRejected() {
        assertThatThrownBy(() -> FieldInventory.of(mutated(field -> field.remove("jsonKey"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id' has 'jsonKey'");
    }

    @Test
    void aRowWithAnUnknownKeyIsRejected() {
        assertThatThrownBy(() -> FieldInventory.of(mutated(field -> field.put("tier", "compatible"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id': key 'tier' is not part of the shape");
    }

    @Test
    void aStatusOutsideTheTiersIsRejected() {
        assertThatThrownBy(() -> FieldInventory.of(
                        mutated(field -> ((ObjectNode) field.get("status").get("lts")).put("tier", "mostly"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id': 'status.lts.tier' is one of");
    }

    @Test
    void aMissingLineStatusIsRejected() {
        assertThatThrownBy(() -> FieldInventory.of(mutated(field -> ((ObjectNode) field.get("status")).remove("lts"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id': 'status.lts' is present");
    }

    @Test
    void aProvenanceOutsideTheVocabularyIsRejected() {
        assertThatThrownBy(() -> FieldInventory.of(mutated(field -> field.put("provenance", "guessed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id': 'provenance' is one of");
    }

    @Test
    void anExampleAFieldNamesMustExist() {
        assertThatThrownBy(() -> FieldInventory.of(
                        mutated(field -> field.putArray("examples").add("no-such-line"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 'id': example 'no-such-line' exists");
    }

    @Test
    void aTransactionExampleWithAQueryKindIsRejected() {
        ObjectNode root = INVENTORY.root().deepCopy();
        ObjectNode example = (ObjectNode) root.get("examples").get(0);
        example.put("type", "transaction");

        assertThatThrownBy(() -> FieldInventory.of(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'kind' is one of [");
    }

    @Test
    void aTransactionExampleRecordWithAKeyThatIsNotATransactionEntryFieldIsRejected() {
        ObjectNode root = INVENTORY.root().deepCopy();
        ObjectNode example = null;
        for (JsonNode candidate : root.get("examples")) {
            if (FieldInventory.isTransaction(candidate)) {
                example = (ObjectNode) candidate;
                break;
            }
        }
        ((ObjectNode) example.get("record")).put("queryText", "RETURN 1");
        ObjectNode transaction = example;

        assertThatThrownBy(() -> FieldInventory.transactionRecord(transaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record key 'queryText' is not a TransactionEntry field");
    }

    @Test
    void anExampleRecordWithAKeyThatIsNotAnEntryFieldIsRejected() {
        ObjectNode root = INVENTORY.root().deepCopy();
        ObjectNode example = (ObjectNode) root.get("examples").get(0);
        ((ObjectNode) example.get("record")).put("colour", "blue");

        assertThatThrownBy(() -> FieldInventory.record(example))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record key 'colour' is not a QueryEntry field");
    }

    @Test
    void aRowsTierAndASettingsDefaultAreReadPerReleaseLine() {
        assertThat(INVENTORY.tier("queryLang", ReleaseLines.CALENDAR)).isEqualTo("compatible");
        assertThat(INVENTORY.tier("queryLang", ReleaseLines.LTS)).isEqualTo("not-applicable");
        assertThat(INVENTORY.tier("db.logs.query.obfuscate_errors", ReleaseLines.LTS))
                .isEqualTo("partial");
        assertThat(INVENTORY.settingDefault("db.logs.query.annotation_data_format", ReleaseLines.CALENDAR))
                .isEqualTo("JSON");
        assertThat(INVENTORY.settingDefault("db.logs.query.annotation_data_format", ReleaseLines.LTS))
                .isEqualTo("CYPHER");
    }

    @Test
    void readingALineTheInventoryDoesNotCarryFails() {
        FieldInventory calendarOnly = FieldInventory.of(withoutLine("lts"));

        assertThatThrownBy(() -> calendarOnly.tier("queryLang", ReleaseLines.LTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no line 'lts'");
        assertThatThrownBy(() -> calendarOnly.settingDefault("db.logs.query.enabled", ReleaseLines.LTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no line 'lts'");
        assertThatThrownBy(() -> INVENTORY.tier("noSuchRow", ReleaseLines.LTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no field or setting named noSuchRow");
    }

    @Test
    void aShapesJsonEntryIsItsOneLiteralLineParsed() {
        JsonNode entry = INVENTORY.jsonEntryOf("router-raised-error");

        assertThat(entry.get("event").asText()).isEqualTo("fail");
        assertThat(entry.get("errorInfo").get("GQLSTATUS").asText()).isEqualTo("50N42");
        assertThatThrownBy(() -> INVENTORY.jsonEntryOf("start-entry"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shape start-entry has no literal JSON line");
    }

    /** A deep copy of the committed inventory without {@code line}: its manual, its defaults and its statuses. */
    private static JsonNode withoutLine(String line) {
        ObjectNode root = INVENTORY.root().deepCopy();
        ((ObjectNode) root.get("lines")).remove(line);
        for (JsonNode setting : root.get("settings")) {
            ((ObjectNode) setting.get("default")).remove(line);
            ((ObjectNode) setting.get("status")).remove(line);
        }
        for (JsonNode field : root.get("fields")) {
            ((ObjectNode) field.get("status")).remove(line);
        }
        return root;
    }

    /** A deep copy of the committed inventory with the {@code id} row changed by {@code change}. */
    private static JsonNode mutated(Consumer<ObjectNode> change) {
        ObjectNode root = INVENTORY.root().deepCopy();
        for (JsonNode field : root.get("fields")) {
            if (field.get("name").asText().equals("id")) {
                change.accept((ObjectNode) field);
                return root;
            }
        }
        throw new IllegalStateException("no 'id' row");
    }

    private static List<JsonNode> elementsOf(JsonNode array) {
        List<JsonNode> elements = new java.util.ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }
}
