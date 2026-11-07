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

import java.util.LinkedHashMap;
import java.util.Map;
import org.neo4j.gqlstatus.ErrorClassification;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;

/**
 * Copies the GQL status object Neo4j reports with a failure into the {@code errorInfo} tree of the JSON entry, as the
 * manual shows it: {@code GQLSTATUS}, {@code statusDescription}, {@code classification} (left out when Neo4j has
 * none), {@code position} with {@code column}, {@code offset} and {@code line} (left out when the failure has no
 * position), and {@code cause}, the same tree for the nested status object, as deep as Neo4j nests it. The result is
 * plain maps and strings, so the entry model never sees a Neo4j type. The position is read from the diagnostic record
 * because the status object exposes it nowhere else.
 *
 * <p>Under {@code db.logs.query.obfuscate_errors} the status description of every level of the tree is the obfuscated
 * one ({@link ObfuscatedErrorDescriptions}), since the message part of a description can embed a data value; the code,
 * the classification and the position carry none and stay.
 */
final class ErrorInfos {

    private ErrorInfos() {}

    static Map<String, Object> of(ErrorGqlStatusObject status, boolean obfuscated) {
        if (status == null) {
            return null;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("GQLSTATUS", status.gqlStatus());
        info.put("statusDescription", obfuscated ? ObfuscatedErrorDescriptions.of(status) : status.statusDescription());
        ErrorClassification classification = status.getClassification();
        if (classification != null && classification != ErrorClassification.UNKNOWN) {
            info.put("classification", classification.name());
        }
        // LTS 5.26: Neo4jDiagnosticRecordProperty does not exist at 5.26.32; "_position" is the literal key
        // DiagnosticRecord.java writes there.
        Object position = status.diagnosticRecord().get("_position");
        if (position instanceof Map<?, ?> at) {
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("column", at.get("column"));
            ordered.put("offset", at.get("offset"));
            ordered.put("line", at.get("line"));
            info.put("position", ordered);
        }
        status.cause().map(cause -> of(cause, obfuscated)).ifPresent(cause -> info.put("cause", cause));
        return info;
    }
}
