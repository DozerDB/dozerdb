/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.entry;

import java.util.Map;

/**
 * What the four renderers share: a field the entry does not carry leaves its plain segment empty and its JSON key
 * out, never the text {@code null} and never a {@code null} value.
 */
final class Rendering {

    private Rendering() {}

    /** Puts the key when the entry carries the value; Log4j would write {@code "key":null} for a null one. */
    static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    /** Appends the segment's text, or nothing for a field the entry does not carry. */
    static void appendText(StringBuilder line, String text) {
        if (text != null) {
            line.append(text);
        }
    }
}
