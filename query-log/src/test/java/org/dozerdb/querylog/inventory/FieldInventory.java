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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.dozerdb.querylog.entry.DetailLevel;
import org.dozerdb.querylog.entry.QueryEntry;
import org.dozerdb.querylog.entry.TransactionEntry;

/**
 * The field inventory as the Java tests read it: {@code scripts/fixtures/query-log-entries.json}, loaded from the
 * classpath through the module's test-resources mapping, so the one committed file is the one the renderer tests, the
 * release-gate check and the docs generator read, and none can disagree with another. {@link #validate} is the
 * documented shape; the Python readers validate the same shape on their side.
 *
 * <p>Shape: {@code lines} (release line id to its manual version), {@code tiers} and {@code provenance} (the allowed
 * words), {@code settings} (one row per documented setting: {@code name}, {@code default} per line, a string or null
 * where that line's Neo4j does not declare the setting, {@code dynamic}, {@code status} per line and {@code notes}),
 * {@code fields} (one row per field or plain-text segment), {@code examples} (one entry each, with the record it is
 * rendered from) and {@code shapes} (the shapes DozerDB defines where the manual is silent: {@code id}, {@code title},
 * the {@code rows} it fixes, the {@code examples} and literal {@code lines} that show it, and its {@code text}). A field
 * row carries {@code name} (unique), {@code type} ({@code common}, {@code query}
 * or {@code transaction}), {@code jsonKey} (or null), {@code plain} (segment, position, separator; or null),
 * {@code levels}, {@code entries} (the kinds of the row's own type, or of both types for a common row), {@code optional}
 * (whether the field may be absent from an entry of a kind and level it appears in: under a detail flag, when Neo4j
 * tracked or reported a value, or when authentication is off), {@code pattern} (a regular expression of the value, or
 * null for an object),
 * {@code examples} (ids of example lines), {@code status} (per line: {@code tier} and {@code reason}),
 * {@code provenance} and {@code notes}. An example carries {@code id}, {@code type} ({@code query}, the default when
 * absent, or {@code transaction}), {@code kind}, {@code level}, {@code detailLevel}, {@code source}, {@code line},
 * {@code stacktrace} and {@code record}. Nothing else is allowed, so a typo is a failure, not a silent extra key.
 */
public final class FieldInventory {

    public static final String RESOURCE = "/query-log-entries.json";

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("description", "lines", "tiers", "provenance", "settings", "fields", "examples", "shapes");
    private static final Set<String> SETTING_KEYS = Set.of("name", "default", "dynamic", "status", "notes");
    private static final Set<String> SHAPE_KEYS = Set.of("id", "title", "rows", "examples", "lines", "text");
    private static final Set<String> FIELD_KEYS = Set.of(
            "name",
            "type",
            "jsonKey",
            "plain",
            "levels",
            "entries",
            "optional",
            "pattern",
            "examples",
            "status",
            "provenance",
            "notes");
    private static final Set<String> PLAIN_KEYS = Set.of("segment", "position", "separator");
    private static final Set<String> STATUS_KEYS = Set.of("tier", "reason");
    private static final Set<String> EXAMPLE_KEYS =
            Set.of("id", "type", "kind", "level", "detailLevel", "source", "line", "stacktrace", "record");
    /** The one example key that may be absent: {@code type} defaults to {@code query}. */
    private static final Set<String> OPTIONAL_EXAMPLE_KEYS = Set.of("type");

    private static final Set<String> EXAMPLE_TYPES = Set.of("query", "transaction");
    private static final Set<String> TYPES = Set.of("common", "query", "transaction");
    private static final Set<String> LEVELS = Set.of("INFO", "VERBOSE");
    private static final Set<String> QUERY_ENTRIES = Set.of("start", "success", "fail");
    private static final Set<String> TRANSACTION_ENTRIES = Set.of("start", "commit", "rollback");
    /** A common row names the kinds of both entry types. */
    private static final Set<String> COMMON_ENTRIES = Set.of("start", "success", "fail", "commit", "rollback");

    private static final Set<String> LOG_LEVELS = Set.of("INFO", "ERROR");

    private final JsonNode root;

    private FieldInventory(JsonNode root) {
        this.root = root;
    }

    /** Loads and validates the committed inventory from the classpath. */
    public static FieldInventory load() {
        try (InputStream in = FieldInventory.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath; the pom maps "
                        + "scripts/fixtures/query-log-entries.json there");
            }
            return of(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Validates {@code root} against the documented shape and wraps it. */
    public static FieldInventory of(JsonNode root) {
        validate(root);
        return new FieldInventory(root);
    }

    public static JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode root() {
        return root;
    }

    /** The release line ids the inventory carries statuses for. */
    public Set<String> lines() {
        Set<String> lines = new HashSet<>();
        root.get("lines").fieldNames().forEachRemaining(lines::add);
        return lines;
    }

    public List<JsonNode> settings() {
        return elements(root.get("settings"));
    }

    public List<JsonNode> fields() {
        return elements(root.get("fields"));
    }

    public List<JsonNode> shapes() {
        return elements(root.get("shapes"));
    }

    public JsonNode shape(String id) {
        return shapes().stream()
                .filter(shape -> shape.get("id").asText().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no shape with the id " + id));
    }

    public List<JsonNode> examples() {
        return elements(root.get("examples"));
    }

    public JsonNode field(String name) {
        return fields().stream()
                .filter(field -> field.get("name").asText().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no field named " + name));
    }

    /**
     * The JSON entry a shape's one literal line shows, parsed, so a test can compare what a running server writes with
     * what the compatibility page shows ({@code router-raised-error}).
     */
    public JsonNode jsonEntryOf(String shapeId) {
        JsonNode lines = shape(shapeId).get("lines");
        if (lines.size() != 1 || !lines.get(0).asText().startsWith("{")) {
            throw new IllegalArgumentException("shape " + shapeId + " has no literal JSON line");
        }
        return parse(lines.get(0).asText());
    }

    /**
     * The compatibility tier of a field or setting row on a release line, so a test can tell what the line's Neo4j
     * cannot supply ({@code not-applicable}) or supplies with a recorded difference ({@code partial}) from what it
     * writes as documented. A line the inventory does not carry is a failure, not an empty tier.
     */
    public String tier(String rowName, ReleaseLines line) {
        JsonNode row = Stream.concat(fields().stream(), settings().stream())
                .filter(candidate -> candidate.get("name").asText().equals(rowName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no field or setting named " + rowName));
        return onLine(row.get("status"), line, rowName).get("tier").asText();
    }

    /**
     * Neo4j's default of a setting on a release line, as the inventory's {@code settings} array records it; null where
     * that line's Neo4j does not declare the setting. A line the inventory does not carry is a failure.
     */
    public String settingDefault(String settingName, ReleaseLines line) {
        JsonNode setting = settings().stream()
                .filter(candidate -> candidate.get("name").asText().equals(settingName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no setting named " + settingName));
        JsonNode value = onLine(setting.get("default"), line, settingName);
        return value.isNull() ? null : value.asText();
    }

    private JsonNode onLine(JsonNode perLine, ReleaseLines line, String rowName) {
        if (!lines().contains(line.id()) || !perLine.has(line.id())) {
            throw new IllegalArgumentException(
                    "the field inventory has no line '" + line.id() + "' for " + rowName + "; it carries " + lines());
        }
        return perLine.get(line.id());
    }

    /** The {@code %m} of an example line as the inventory fixes it. */
    public static String line(JsonNode example) {
        return example.get("line").asText();
    }

    /** Whether the example is a transaction entry ({@code "type": "transaction"}) rather than a query entry. */
    public static boolean isTransaction(JsonNode example) {
        return example.path("type").asText("query").equals("transaction");
    }

    /** The transaction entry an example line is rendered from, built from its {@code record} object. */
    public static TransactionEntry transactionRecord(JsonNode example) {
        if (!isTransaction(example)) {
            throw new IllegalArgumentException("example " + example.get("id").asText() + " is a query entry");
        }
        TransactionEntry.Builder builder = TransactionEntry.builder()
                .kind(TransactionEntry.Kind.valueOf(example.get("kind").asText().toUpperCase()));
        JsonNode record = example.get("record");
        for (Iterator<Map.Entry<String, JsonNode>> it = record.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "transactionId" -> builder.transactionId(value.asLong());
                case "openMillis" -> builder.openMillis(value.asLong());
                case "source" -> builder.source(text(value));
                case "database" -> builder.database(text(value));
                case "executingUser" -> builder.executingUser(text(value));
                case "authenticatedUser" -> builder.authenticatedUser(text(value));
                case "annotationData" -> builder.annotationData(plainMap(value));
                case "failureReason" -> builder.failureReason(text(value));
                default -> throw new IllegalArgumentException(
                        "example " + example.get("id").asText() + ": record key '" + entry.getKey()
                                + "' is not a TransactionEntry field");
            }
        }
        if (example.get("stacktrace").asBoolean()) {
            // The throwable itself cannot be in the inventory; an example with a stack trace stands for a rollback
            // Neo4j reported with one, whose message is the reason.
            builder.failureCause(
                    new RuntimeException(record.path("failureReason").asText(null)));
        }
        return builder.build();
    }

    /** The query entry an example line is rendered from, built from its {@code record} object. */
    public static QueryEntry record(JsonNode example) {
        if (isTransaction(example)) {
            throw new IllegalArgumentException("example " + example.get("id").asText() + " is a transaction entry");
        }
        QueryEntry.Builder builder = QueryEntry.builder()
                .kind(QueryEntry.Kind.valueOf(example.get("kind").asText().toUpperCase()))
                .detailLevel(DetailLevel.valueOf(example.get("detailLevel").asText()));
        JsonNode record = example.get("record");
        for (Iterator<Map.Entry<String, JsonNode>> it = record.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "queryId" -> builder.queryId(value.asLong());
                case "transactionId" -> builder.transactionId(value.asLong());
                case "elapsedMillis" -> builder.elapsedMillis(value.asLong());
                case "planningMillis" -> builder.planningMillis(value.asLong());
                case "cpuMillis" -> builder.cpuMillis(value.isNull() ? null : value.asLong());
                case "waitingMillis" -> builder.waitingMillis(value.asLong());
                case "allocatedBytes" -> builder.allocatedBytes(value.asLong());
                case "pageHits" -> builder.pageHits(value.asLong());
                case "pageFaults" -> builder.pageFaults(value.asLong());
                case "source" -> builder.source(text(value));
                case "database" -> builder.database(text(value));
                case "executingUser" -> builder.executingUser(text(value));
                case "authenticatedUser" -> builder.authenticatedUser(text(value));
                case "queryText" -> builder.queryText(text(value));
                case "parameters" -> builder.parameters(text(value));
                case "runtime" -> builder.runtime(text(value));
                case "annotationData" -> builder.annotationData(plainMap(value));
                case "queryLanguage" -> builder.queryLanguage(text(value));
                case "executionPlanCacheKeyHash" -> builder.executionPlanCacheKeyHash(text(value));
                case "queryPlan" -> builder.queryPlan(text(value));
                case "failureReason" -> builder.failureReason(text(value));
                case "errorInfo" -> builder.errorInfo(value.isNull() ? null : plainMap(value));
                default -> throw new IllegalArgumentException(
                        "example " + example.get("id").asText() + ": record key '" + entry.getKey()
                                + "' is not a QueryEntry field");
            }
        }
        return builder.build();
    }

    /** Validates the documented shape; the message names the offending row and key. */
    public static void validate(JsonNode root) {
        require(root.isObject(), "the inventory is a JSON object");
        onlyKeys(root, TOP_LEVEL_KEYS, "the inventory");
        for (String key : TOP_LEVEL_KEYS) {
            require(root.has(key), "the inventory has '" + key + "'");
        }
        require(root.get("description").isTextual(), "'description' is a string");
        JsonNode lines = root.get("lines");
        require(lines.isObject() && !lines.isEmpty(), "'lines' is a non-empty object");
        lines.fields()
                .forEachRemaining(line -> require(
                        line.getValue().isObject()
                                && line.getValue().path("manual").isTextual(),
                        "line '" + line.getKey() + "' names its 'manual' version"));
        Set<String> tiers = words(root.get("tiers"), "tiers");
        Set<String> provenance = words(root.get("provenance"), "provenance");

        Set<String> exampleIds = new HashSet<>();
        for (JsonNode example : elements(root.get("examples"))) {
            validateExample(example, exampleIds);
        }
        Set<String> names = new HashSet<>();
        for (JsonNode field : elements(root.get("fields"))) {
            validateField(field, lines, tiers, provenance, exampleIds, names);
        }
        Set<String> settingNames = new HashSet<>();
        for (JsonNode setting : elements(root.get("settings"))) {
            validateSetting(setting, lines, tiers, settingNames);
        }
        Set<String> shapeIds = new HashSet<>();
        for (JsonNode shape : elements(root.get("shapes"))) {
            validateShape(shape, names, settingNames, exampleIds, shapeIds);
        }
    }

    private static void validateSetting(JsonNode setting, JsonNode lines, Set<String> tiers, Set<String> names) {
        require(setting.isObject() && setting.path("name").isTextual(), "every setting row is an object with a 'name'");
        String row = "setting '" + setting.get("name").asText() + "'";
        require(names.add(setting.get("name").asText()), row + " is named once");
        onlyKeys(setting, SETTING_KEYS, row);
        for (String key : SETTING_KEYS) {
            require(setting.has(key), row + " has '" + key + "'");
        }
        JsonNode defaults = setting.get("default");
        require(defaults.isObject(), row + ": 'default' is an object");
        onlyKeys(defaults, keys(lines), row + ".default");
        for (Iterator<String> it = lines.fieldNames(); it.hasNext(); ) {
            String line = it.next();
            JsonNode value = defaults.get(line);
            require(
                    value != null && (value.isNull() || value.isTextual()),
                    row + ": 'default." + line + "' is a string or null");
        }
        require(setting.get("dynamic").isBoolean(), row + ": 'dynamic' is true or false");
        validateStatus(setting.get("status"), lines, tiers, row);
        require(setting.get("notes").isTextual(), row + ": 'notes' is a string");
    }

    private static void validateShape(
            JsonNode shape, Set<String> rows, Set<String> settings, Set<String> exampleIds, Set<String> ids) {
        require(shape.isObject() && shape.path("id").isTextual(), "every shape is an object with an 'id'");
        String row = "shape '" + shape.get("id").asText() + "'";
        require(ids.add(shape.get("id").asText()), row + " is identified once");
        onlyKeys(shape, SHAPE_KEYS, row);
        for (String key : SHAPE_KEYS) {
            require(shape.has(key), row + " has '" + key + "'");
        }
        require(shape.get("title").isTextual(), row + ": 'title' is a string");
        require(shape.get("text").isTextual(), row + ": 'text' is a string");
        for (String name : words(shape.get("rows"), row + ".rows")) {
            require(rows.contains(name) || settings.contains(name), row + ": row '" + name + "' exists");
        }
        for (String example : words(shape.get("examples"), row + ".examples")) {
            require(exampleIds.contains(example), row + ": example '" + example + "' exists");
        }
        words(shape.get("lines"), row + ".lines");
    }

    private static void validateStatus(JsonNode status, JsonNode lines, Set<String> tiers, String row) {
        require(status != null && status.isObject(), row + ": 'status' is an object");
        onlyKeys(status, keys(lines), row + ".status");
        for (Iterator<String> it = lines.fieldNames(); it.hasNext(); ) {
            String line = it.next();
            JsonNode lineStatus = status.get(line);
            require(lineStatus != null && lineStatus.isObject(), row + ": 'status." + line + "' is present");
            onlyKeys(lineStatus, STATUS_KEYS, row + ".status." + line);
            String tier = lineStatus.path("tier").asText(null);
            require(tier != null && tiers.contains(tier), row + ": 'status." + line + ".tier' is one of " + tiers);
            require(lineStatus.path("reason").isTextual(), row + ": 'status." + line + ".reason' is a string");
        }
    }

    private static void validateField(
            JsonNode field,
            JsonNode lines,
            Set<String> tiers,
            Set<String> provenance,
            Set<String> exampleIds,
            Set<String> names) {
        require(field.isObject() && field.path("name").isTextual(), "every field row is an object with a 'name'");
        String name = field.get("name").asText();
        String row = "field '" + name + "'";
        require(names.add(name), row + " is named once");
        onlyKeys(field, FIELD_KEYS, row);
        for (String key : FIELD_KEYS) {
            require(field.has(key), row + " has '" + key + "'");
        }
        String type = field.get("type").asText();
        require(TYPES.contains(type), row + ": 'type' is one of " + TYPES);
        JsonNode jsonKey = field.get("jsonKey");
        require(jsonKey.isNull() || jsonKey.isTextual(), row + ": 'jsonKey' is a string or null");
        JsonNode plain = field.get("plain");
        require(plain.isNull() || plain.isObject(), row + ": 'plain' is an object or null");
        if (plain.isObject()) {
            onlyKeys(plain, PLAIN_KEYS, row + ".plain");
            require(plain.path("segment").isTextual(), row + ": 'plain.segment' is a string");
            require(plain.path("position").isInt(), row + ": 'plain.position' is an integer");
            require(plain.path("separator").isTextual(), row + ": 'plain.separator' is a string");
        }
        require(!jsonKey.isNull() || plain.isObject(), row + " has a JSON key or a plain segment");
        for (String level : words(field.get("levels"), row + ".levels")) {
            require(LEVELS.contains(level), row + ": level '" + level + "' is one of " + LEVELS);
        }
        Set<String> allowedEntries =
                switch (type) {
                    case "transaction" -> TRANSACTION_ENTRIES;
                    case "query" -> QUERY_ENTRIES;
                    default -> COMMON_ENTRIES;
                };
        for (String entry : words(field.get("entries"), row + ".entries")) {
            require(allowedEntries.contains(entry), row + ": entry kind '" + entry + "' is one of " + allowedEntries);
        }
        require(field.get("optional").isBoolean(), row + ": 'optional' is true or false");
        JsonNode pattern = field.get("pattern");
        require(pattern.isNull() || pattern.isTextual(), row + ": 'pattern' is a string or null");
        if (pattern.isTextual()) {
            try {
                Pattern.compile(pattern.asText());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(row + ": 'pattern' is not a regular expression: " + e.getMessage());
            }
        }
        for (String example : words(field.get("examples"), row + ".examples")) {
            require(exampleIds.contains(example), row + ": example '" + example + "' exists");
        }
        validateStatus(field.get("status"), lines, tiers, row);
        String rowProvenance = field.get("provenance").asText(null);
        require(
                rowProvenance != null && provenance.contains(rowProvenance),
                row + ": 'provenance' is one of " + provenance);
        require(field.get("notes").isTextual(), row + ": 'notes' is a string");
    }

    private static void validateExample(JsonNode example, Set<String> ids) {
        require(example.isObject() && example.path("id").isTextual(), "every example is an object with an 'id'");
        String row = "example '" + example.get("id").asText() + "'";
        require(ids.add(example.get("id").asText()), row + " is identified once");
        onlyKeys(example, EXAMPLE_KEYS, row);
        for (String key : EXAMPLE_KEYS) {
            require(OPTIONAL_EXAMPLE_KEYS.contains(key) || example.has(key), row + " has '" + key + "'");
        }
        String type = example.path("type").asText("query");
        require(EXAMPLE_TYPES.contains(type), row + ": 'type' is one of " + EXAMPLE_TYPES);
        Set<String> kinds = type.equals("transaction") ? TRANSACTION_ENTRIES : QUERY_ENTRIES;
        require(kinds.contains(example.get("kind").asText()), row + ": 'kind' is one of " + kinds);
        require(LOG_LEVELS.contains(example.get("level").asText()), row + ": 'level' is one of " + LOG_LEVELS);
        require(LEVELS.contains(example.get("detailLevel").asText()), row + ": 'detailLevel' is one of " + LEVELS);
        require(example.get("source").isTextual(), row + ": 'source' is a string");
        require(example.get("line").isTextual(), row + ": 'line' is a string");
        require(example.get("stacktrace").isBoolean(), row + ": 'stacktrace' is a boolean");
        require(example.get("record").isObject(), row + ": 'record' is an object");
    }

    private static Set<String> words(JsonNode array, String what) {
        require(array != null && array.isArray(), "'" + what + "' is an array of strings");
        Set<String> words = new HashSet<>();
        for (JsonNode word : array) {
            require(word.isTextual(), "'" + what + "' holds strings only");
            words.add(word.asText());
        }
        return words;
    }

    private static void onlyKeys(JsonNode object, Set<String> allowed, String what) {
        for (String key : keys(object)) {
            require(allowed.contains(key), what + ": key '" + key + "' is not part of the shape " + allowed);
        }
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> keys = new HashSet<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<JsonNode> elements(JsonNode array) {
        require(array != null && array.isArray(), "expected an array");
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }

    private static String text(JsonNode value) {
        return value.isNull() ? null : value.asText();
    }

    private static Map<String, Object> plainMap(JsonNode object) {
        Map<String, Object> map = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> map.put(entry.getKey(), plainValue(entry.getValue())));
        return map;
    }

    private static Object plainValue(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            return plainMap(value);
        }
        if (value.isArray()) {
            List<Object> list = new ArrayList<>();
            value.forEach(element -> list.add(plainValue(element)));
            return list;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asText();
    }

    private static void require(boolean condition, String expectation) {
        if (!condition) {
            throw new IllegalArgumentException("Field inventory: expected that " + expectation);
        }
    }
}
