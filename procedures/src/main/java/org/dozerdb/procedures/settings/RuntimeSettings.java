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
 * The live configuration of the running server as a runtime setting change sees it: a setting is named by its
 * documented name and its new value arrives as the text an administrator typed. The upstream adapter implements it
 * over Neo4j's configuration; this interface keeps the rule of the procedure free of Neo4j classes.
 *
 * <p>Every method fails with an {@link IllegalArgumentException} whose message names the setting and the reason when
 * the setting is unknown, is not dynamic (cannot change without a restart) or the value does not parse or validate;
 * the configuration is then unchanged.
 */
public interface RuntimeSettings {

    /** Sets the setting to the parsed value for the running server; every listener of the setting hears the change. */
    void set(String setting, String value);

    /** Removes the configured value of the setting so that it falls back to its default, for the running server. */
    void reset(String setting);
}
