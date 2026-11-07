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

/**
 * The rule of {@code dbms.setConfigValue}, as the Operations Manual states it: the value is set for the running
 * server, and an empty value removes the configured value so that the setting falls back to its default. The change
 * never persists; a restart reads {@code neo4j.conf} again.
 */
public final class SettingChange {

    private SettingChange() {}

    /**
     * Applies {@code value} to {@code setting}: an empty value resets the setting, any other value sets it.
     *
     * @throws IllegalArgumentException when either argument is null or the settings refuse the change
     */
    public static void apply(RuntimeSettings settings, String setting, String value) {
        if (setting == null || setting.isBlank()) {
            throw new IllegalArgumentException(
                    "The setting argument must name a setting, for example 'db.logs.query.enabled'.");
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "The value argument must be a string; pass '' to reset '" + setting + "' to its default value.");
        }
        String name = setting.strip();
        if (value.isEmpty()) {
            settings.reset(name);
        } else {
            settings.set(name, value);
        }
    }
}
