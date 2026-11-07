/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.neo4j.values.AnyValue;
import org.neo4j.values.AnyValueWriter.EntityMode;
import org.neo4j.values.storable.Values;
import org.neo4j.values.utils.PrettyPrinter;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.VirtualValues;

/**
 * The parameters segment as the adapter renders it from Neo4j's parameter map: the manual's single-quoted Cypher map,
 * {@code db.logs.query.max_parameter_length} applied to each value with the {@code ...} inside the value.
 */
class ParameterMapsTest {

    private static final int UNLIMITED = Integer.MAX_VALUE;

    @Test
    void aMapParameterRendersAsTheManualsSingleQuotedCypherMap() {
        MapValue parameters = map("baz", map("k", Values.longValue(1)));

        assertThat(ParameterMaps.render(parameters, UNLIMITED)).isEqualTo("{baz: {k: 1}}");
    }

    @Test
    void withNoMaximumTheRenderingIsNeo4jsOwnPrinterWithTheSingleQuoteMark() {
        MapValue parameters = map("foo", Values.stringValue("bar"), "baz", map("k", Values.longValue(1)));
        PrettyPrinter wholeMap = new PrettyPrinter("'", EntityMode.REFERENCE);
        parameters.writeTo(wholeMap);

        assertThat(ParameterMaps.render(parameters, UNLIMITED))
                .isEqualTo(wholeMap.value())
                .containsOnlyOnce("foo: 'bar'")
                .containsOnlyOnce("baz: {k: 1}");
    }

    @Test
    void aListParameterRendersAsACypherList() {
        MapValue parameters =
                map("ids", VirtualValues.list(Values.longValue(1), Values.longValue(2), Values.stringValue("three")));

        assertThat(ParameterMaps.render(parameters, UNLIMITED)).isEqualTo("{ids: [1, 2, 'three']}");
    }

    @Test
    void noParametersRenderAsTheEmptyMap() {
        assertThat(ParameterMaps.render(VirtualValues.EMPTY_MAP, UNLIMITED)).isEqualTo("{}");
    }

    @Test
    void aStringOverTheMaximumLengthIsCutInsideItsQuotesWithAnEllipsis() {
        MapValue parameters = map("name", Values.stringValue("Alexander the Great"), "n", Values.longValue(42));

        assertThat(ParameterMaps.render(parameters, 10)).isEqualTo("{name: 'Alexander ...', n: 42}");
    }

    @Test
    void aStringAtTheMaximumLengthIsNotCut() {
        MapValue parameters = map("name", Values.stringValue("Alexander"));

        assertThat(ParameterMaps.render(parameters, 9)).isEqualTo("{name: 'Alexander'}");
    }

    @Test
    void theMaximumLengthAppliesToEachValueNotToTheWholeMap() {
        MapValue parameters = map("a", Values.stringValue("12345"), "b", Values.stringValue("67890"));

        assertThat(ParameterMaps.render(parameters, 5)).isEqualTo("{a: '12345', b: '67890'}");
    }

    @Test
    void aCompoundValueOverTheMaximumLengthIsCutWithTheEllipsisInsideTheValue() {
        MapValue parameters = map(
                "ids",
                VirtualValues.list(Values.longValue(100), Values.longValue(200), Values.longValue(300)),
                "nested",
                map("k", Values.stringValue("a long nested string")));

        assertThat(ParameterMaps.render(parameters, 8)).isEqualTo("{ids: [100, 20..., nested: {k: 'a l...}");
    }

    @Test
    void aNullValueRendersAsNeo4jsPrettyPrinterWritesIt() {
        MapValue parameters = map("p", Values.NO_VALUE);

        assertThat(ParameterMaps.render(parameters, UNLIMITED)).isEqualTo("{p: <null>}");
    }

    @Test
    void aStringIsCutOnCharactersAndNeverInsideASurrogatePair() {
        MapValue parameters = map("emoji", Values.stringValue("ab😀cd"));

        assertThat(ParameterMaps.render(parameters, 3)).isEqualTo("{emoji: 'ab😀...'}");
    }

    private static MapValue map(String key, AnyValue value) {
        return VirtualValues.map(new String[] {key}, new AnyValue[] {value});
    }

    private static MapValue map(String key1, AnyValue value1, String key2, AnyValue value2) {
        return VirtualValues.map(new String[] {key1, key2}, new AnyValue[] {value1, value2});
    }
}
