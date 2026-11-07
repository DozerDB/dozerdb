/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.procedures.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The manual's rule for the value argument: a value sets, an empty value resets, and a missing argument is refused. */
class SettingChangeTest {

    private final List<String> applied = new ArrayList<>();
    private final RuntimeSettings settings = new RuntimeSettings() {
        @Override
        public void set(String setting, String value) {
            applied.add("set " + setting + "=" + value);
        }

        @Override
        public void reset(String setting) {
            applied.add("reset " + setting);
        }
    };

    @Test
    void aValueIsSetOnTheNamedSetting() {
        SettingChange.apply(settings, "db.logs.query.enabled", "INFO");

        assertThat(applied).containsExactly("set db.logs.query.enabled=INFO");
    }

    @Test
    void anEmptyValueResetsTheSettingToItsDefault() {
        SettingChange.apply(settings, "db.logs.query.threshold", "");

        assertThat(applied).containsExactly("reset db.logs.query.threshold");
    }

    @Test
    void surroundingWhitespaceAroundTheSettingNameIsIgnoredButTheValueIsPassedAsTyped() {
        SettingChange.apply(settings, " db.logs.query.threshold ", " 2s");

        assertThat(applied).containsExactly("set db.logs.query.threshold= 2s");
    }

    @Test
    void aMissingSettingNameIsRefused() {
        assertThatThrownBy(() -> SettingChange.apply(settings, null, "INFO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name a setting");
        assertThatThrownBy(() -> SettingChange.apply(settings, "  ", "INFO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name a setting");
        assertThat(applied).isEmpty();
    }

    @Test
    void aNullValueIsRefusedAndTheResetSpelledOut() {
        assertThatThrownBy(() -> SettingChange.apply(settings, "db.logs.query.enabled", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass '' to reset 'db.logs.query.enabled'");
        assertThat(applied).isEmpty();
    }
}
