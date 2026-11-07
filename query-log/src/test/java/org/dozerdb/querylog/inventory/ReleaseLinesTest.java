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
import org.junit.jupiter.api.Test;

/**
 * The release line is derived from the running kernel version, so the same test sources judge the inventory's
 * statuses for whichever line they are built against.
 */
class ReleaseLinesTest {

    @Test
    void theKernelVersionNamesTheLine() {
        assertThat(ReleaseLines.of("2026.10.1")).isEqualTo(ReleaseLines.CALENDAR);
        assertThat(ReleaseLines.of("2027.01.0")).isEqualTo(ReleaseLines.CALENDAR);
        assertThat(ReleaseLines.of("5.26.32")).isEqualTo(ReleaseLines.LTS);
        assertThat(ReleaseLines.of("5.26.32-SNAPSHOT")).isEqualTo(ReleaseLines.LTS);
        assertThatThrownBy(() -> ReleaseLines.of("4.4.42")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseLines.of("dev")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRunningKernelIsOnALineTheInventoryCarries() {
        FieldInventory inventory = FieldInventory.load();
        ReleaseLines line = ReleaseLines.current();

        assertThat(inventory.lines()).contains(line.id());
        for (JsonNode field : inventory.fields()) {
            assertThat(field.get("status").has(line.id()))
                    .as(
                            "field %s has a status for the running line %s",
                            field.get("name").asText(), line)
                    .isTrue();
        }
    }

    @Test
    void theRunningLinesNotApplicableFieldsAreTheOnesItsKernelCannotSupply() {
        FieldInventory inventory = FieldInventory.load();
        ReleaseLines line = ReleaseLines.current();

        var notApplicable = inventory.fields().stream()
                .filter(field ->
                        inventory.tier(field.get("name").asText(), line).equals("not-applicable"))
                .map(field -> field.get("name").asText())
                .toList();
        if (line == ReleaseLines.CALENDAR) {
            assertThat(notApplicable).isEmpty();
        } else {
            assertThat(notApplicable).containsExactly("queryLang");
        }
    }
}
