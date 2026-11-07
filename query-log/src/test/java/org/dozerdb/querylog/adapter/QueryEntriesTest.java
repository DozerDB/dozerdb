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

import java.util.Map;
import org.dozerdb.querylog.adapter.ObfuscatedErrorDescriptionsTest.WithoutObfuscatedDescription;
import org.dozerdb.querylog.entry.QueryEntry;
import org.junit.jupiter.api.Test;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.gqlstatus.ErrorGqlStatusObjectImplementation;
import org.neo4j.gqlstatus.GqlParams;
import org.neo4j.gqlstatus.GqlStatusInfoCodes;

/**
 * What the failure part of a failed-query entry carries under {@code db.logs.query.obfuscate_errors}: the obfuscated
 * description in place of the message, both in the plain reason and in the {@code errorInfo} tree, and no throwable,
 * so the entry has no stack trace. Without the setting the entry carries what Neo4j reported.
 */
class QueryEntriesTest {

    private static final RuntimeException FAILURE = new RuntimeException("Cannot parse '2024-13-45' as a DATE");

    private static ErrorGqlStatusObject statusWithASensitiveInput() {
        return ErrorGqlStatusObjectImplementation.from(GqlStatusInfoCodes.STATUS_22N36)
                .withParam(GqlParams.StringParam.input, "2024-13-45")
                .withParam(GqlParams.StringParam.valueType, "DATE")
                .withCause(ErrorGqlStatusObjectImplementation.from(GqlStatusInfoCodes.STATUS_22N36)
                        .withParam(GqlParams.StringParam.input, "13-45")
                        .withParam(GqlParams.StringParam.valueType, "TIME")
                        .build())
                .build();
    }

    @Test
    void withoutObfuscationTheEntryCarriesNeo4jsMessageTheThrowableAndTheDescriptions() {
        QueryEntry entry = QueryEntries.failure(
                        QueryEntry.builder(), FAILURE.getMessage(), FAILURE, statusWithASensitiveInput(), false)
                .build();

        assertThat(entry.failureReason()).isEqualTo("Cannot parse '2024-13-45' as a DATE");
        assertThat(entry.failureCause()).isSameAs(FAILURE);
        assertThat(entry.errorInfo().get("statusDescription").toString()).contains("2024-13-45");
        assertThat(causeOf(entry).get("statusDescription").toString()).contains("13-45");
    }

    @Test
    void underObfuscateErrorsTheObfuscatedDescriptionReplacesTheMessageAndThereIsNoThrowable() {
        QueryEntry entry = QueryEntries.failure(
                        QueryEntry.builder(), FAILURE.getMessage(), FAILURE, statusWithASensitiveInput(), true)
                .build();

        String standard = "error: data exception - invalid temporal format";
        String outer = standard + ". Cannot parse '******' as a DATE.";
        String inner = standard + ". Cannot parse '******' as a TIME.";
        if (ObfuscatedErrorDescriptionsTest.standardDescriptionOnly()) {
            // Neo4j 5.26 has no obfuscatedStatusDescription(), so the standard description of the code stands in at
            // every level and the masked message never appears.
            outer = standard;
            inner = standard;
        }
        assertThat(entry.failureReason()).isEqualTo(outer);
        assertThat(entry.failureCause()).as("no throwable, so no stack trace").isNull();
        assertThat(entry.errorInfo().get("GQLSTATUS")).isEqualTo("22N36");
        assertThat(entry.errorInfo().get("statusDescription")).isEqualTo(outer);
        assertThat(causeOf(entry).get("statusDescription")).isEqualTo(inner);
    }

    @Test
    void underObfuscateErrorsALineWithoutTheObfuscatedDescriptionWritesTheStandardDescriptionOfTheCode() {
        QueryEntry entry = QueryEntries.failure(
                        QueryEntry.builder(),
                        FAILURE.getMessage(),
                        FAILURE,
                        new WithoutObfuscatedDescription("22N36"),
                        true)
                .build();

        assertThat(entry.failureReason()).isEqualTo("error: data exception - invalid temporal format");
        assertThat(entry.failureCause()).isNull();
        assertThat(entry.errorInfo())
                .containsEntry("GQLSTATUS", "22N36")
                .containsEntry("statusDescription", "error: data exception - invalid temporal format")
                .doesNotContainKey("cause");
    }

    @Test
    void underObfuscateErrorsAFailureWithoutAStatusObjectCarriesNoReasonAtAll() {
        QueryEntry entry = QueryEntries.failure(QueryEntry.builder(), FAILURE.getMessage(), FAILURE, null, true)
                .build();

        assertThat(entry.failureReason())
                .as("the message could embed data and nothing safe stands in")
                .isNull();
        assertThat(entry.failureCause()).isNull();
        assertThat(entry.errorInfo()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> causeOf(QueryEntry entry) {
        return (Map<String, Object>) entry.errorInfo().get("cause");
    }
}
