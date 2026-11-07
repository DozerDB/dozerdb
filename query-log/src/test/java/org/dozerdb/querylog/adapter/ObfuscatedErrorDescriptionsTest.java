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

import org.dozerdb.querylog.inventory.FieldInventory;
import org.dozerdb.querylog.inventory.ReleaseLines;
import org.junit.jupiter.api.Test;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.gqlstatus.ErrorGqlStatusObjectImplementation;
import org.neo4j.gqlstatus.GqlParams;
import org.neo4j.gqlstatus.GqlStatusInfoCodes;

/**
 * The description a failed-query entry carries under {@code db.logs.query.obfuscate_errors}: Neo4j's obfuscated
 * status description where the line offers one, and otherwise the standard description of the GQLSTATUS code alone
 * (condition and sub-condition, no message), which is all a line without that accessor can say without risking data.
 */
class ObfuscatedErrorDescriptionsTest {

    @Test
    void neo4jsObfuscatedDescriptionMasksTheSensitiveParametersAndKeepsTheOthersWhereTheLineHasOne() {
        ErrorGqlStatusObject status = ErrorGqlStatusObjectImplementation.from(GqlStatusInfoCodes.STATUS_22N36)
                .withParam(GqlParams.StringParam.input, "2024-13-45")
                .withParam(GqlParams.StringParam.valueType, "DATE")
                .build();

        assertThat(status.statusDescription()).contains("2024-13-45");
        String obfuscated = ObfuscatedErrorDescriptions.of(status);
        assertThat(obfuscated)
                .startsWith("error: data exception - invalid temporal format")
                .doesNotContain("2024-13-45");
        if (standardDescriptionOnly()) {
            // Neo4j 5.26 has no obfuscatedStatusDescription(), so the standard description is all the entry says.
            assertThat(obfuscated).isEqualTo("error: data exception - invalid temporal format");
        } else {
            assertThat(obfuscated)
                    .startsWith("error: data exception - invalid temporal format. Cannot parse ")
                    .contains("******")
                    .contains("DATE");
        }
    }

    @Test
    void withoutAnObfuscatedDescriptionTheStandardDescriptionOfTheCodeStandsIn() {
        ErrorGqlStatusObject status = new WithoutObfuscatedDescription("42N62");

        assertThat(ObfuscatedErrorDescriptions.of(status))
                .isEqualTo("error: syntax error or access rule violation - variable not defined");
    }

    @Test
    void anUnknownCodeFallsBackToTheStandardDescriptionOfTheDefaultCode() {
        ErrorGqlStatusObject status = new WithoutObfuscatedDescription("99Z99");

        assertThat(ObfuscatedErrorDescriptions.of(status))
                .isEqualTo("error: general processing exception - unexpected error");
    }

    /**
     * Whether the running line's Neo4j lacks the obfuscated status description, so {@code db.logs.query.obfuscate_errors}
     * writes the standard description alone: the inventory tiers the setting Partial on that line.
     */
    static boolean standardDescriptionOnly() {
        return FieldInventory.load()
                .tier("db.logs.query.obfuscate_errors", ReleaseLines.current())
                .equals("partial");
    }

    /**
     * A status object whose inner object is not Neo4j's implementation, so {@code obfuscatedStatusDescription()} is
     * empty, as it is on a line without the accessor.
     */
    static final class WithoutObfuscatedDescription implements ErrorGqlStatusObject {
        private final String code;

        WithoutObfuscatedDescription(String code) {
            this.code = code;
        }

        @Override
        public String gqlStatus() {
            return code;
        }

        @Override
        public String statusDescription() {
            return "a description that embeds the value 'secret'";
        }

        @Override
        public ErrorGqlStatusObject gqlStatusObject() {
            return null;
        }

        @Override
        public String getMessage() {
            return code + ": a message that embeds the value 'secret'";
        }

        @Override
        public String legacyMessage() {
            return "";
        }
    }
}
