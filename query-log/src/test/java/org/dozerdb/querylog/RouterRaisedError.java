/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog;

import com.fasterxml.jackson.databind.JsonNode;
import org.dozerdb.querylog.inventory.FieldInventory;
import org.dozerdb.querylog.inventory.ReleaseLines;

/**
 * The failure Neo4j 5.26 raises without a GQL status of its own ("variable not defined" is raised by the router), as
 * the field inventory's {@code router-raised-error} shape records its JSON entry. {@code errorInfo} is Partial on the
 * line where it happens, so the in-server tests that provoke it ask the inventory whether to expect it and what it
 * carries, instead of naming a line or repeating its text.
 */
final class RouterRaisedError {

    private static final FieldInventory INVENTORY = FieldInventory.load();
    private static final JsonNode ERROR_INFO =
            INVENTORY.jsonEntryOf("router-raised-error").get("errorInfo");

    private RouterRaisedError() {}

    /** Whether the running line raises the error without a status: {@code errorInfo} is Partial there. */
    static boolean onTheRunningLine() {
        return INVENTORY.tier("errorInfo", ReleaseLines.current()).equals("partial");
    }

    /** The default status the router reports, {@code 50N42}. */
    static String status() {
        return ERROR_INFO.get("GQLSTATUS").asText();
    }

    /**
     * The standard description of that status, its condition and sub-condition without the message, which
     * {@code db.logs.query.obfuscate_errors} writes in place of the failure reason on a line without an obfuscated
     * description.
     */
    static String standardDescription() {
        String full = ERROR_INFO.get("statusDescription").asText();
        return full.substring(0, full.indexOf(". "));
    }
}
