/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.procedures.adapter;

import org.dozerdb.procedures.settings.RuntimeSettings;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.SettingImpl;
import org.neo4j.graphdb.config.Setting;

/**
 * Applies a runtime setting change to the kernel's live {@link Config} the way Neo4j's own procedure does: through
 * the dynamic-by-user path, so that the setting's change listeners run (the query log's among them), the value is
 * reported as set by a user, and {@code debug.log} records who changed what. Neo4j itself refuses a setting that is
 * not dynamic and a value that fails validation; this adapter adds the unknown-setting and unparsable-value cases
 * with the same kind of message, and parses the typed text with the setting's own parser so every type and
 * constraint the manual documents applies.
 */
final class ConfigurationAdapter implements RuntimeSettings {

    private final Config config;
    private final String scope;

    /**
     * @param scope who makes the change, for the configuration's own log line ({@code <setting> changed to <value>,
     *     by <scope>})
     */
    ConfigurationAdapter(Config config, String scope) {
        this.config = config;
        this.scope = scope;
    }

    @Override
    public void set(String setting, String value) {
        apply(declared(setting), value);
    }

    @Override
    public void reset(String setting) {
        // A null value is how the configuration represents "not configured": the entry falls back to its default and
        // reports the default as its source.
        config.setDynamicByUser(declared(setting), null, scope);
    }

    private <T> void apply(SettingImpl<T> setting, String value) {
        T parsed;
        try {
            parsed = setting.parse(value);
        } catch (RuntimeException e) {
            // Not chained as the cause: the client is shown the root cause's message, which would lose the setting.
            throw new IllegalArgumentException(
                    "Invalid value '" + value + "' for setting '" + setting.name() + "': " + e.getMessage());
        }
        config.setDynamicByUser(setting, parsed, scope);
    }

    @SuppressWarnings("unchecked")
    private SettingImpl<Object> declared(String name) {
        Setting<Object> setting = config.getDeclaredSettings().get(name);
        if (setting == null) {
            throw new IllegalArgumentException("Unknown setting '" + name + "': no such setting is declared.");
        }
        return (SettingImpl<Object>) setting;
    }
}
