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

import java.util.HashMap;
import java.util.Map;
import org.neo4j.gqlstatus.Condition;
import org.neo4j.gqlstatus.ErrorGqlStatusObject;
import org.neo4j.gqlstatus.GqlStatusInfoCodes;

/**
 * The text a failed-query entry carries in place of the failure message under {@code db.logs.query.obfuscate_errors}:
 * a status description that cannot embed a data value. Neo4j's calendar line offers
 * {@code ErrorGqlStatusObject.obfuscatedStatusDescription()}, the condition and sub-condition plus the GQL message
 * with every sensitive parameter replaced by {@code ******} (a variable name stays, a parsed input does not). The
 * 5.26 line has no such accessor, so when the status object yields no obfuscated description the standard description
 * of its GQLSTATUS code stands in: the condition and sub-condition alone ({@code error: syntax error or access rule
 * violation - variable not defined}), with no message at all, and the general processing error's for a code Neo4j's
 * table does not know. The LTS port switches only the first line of {@link #of}; the fallback and its test are shared.
 */
final class ObfuscatedErrorDescriptions {

    private static final Map<String, GqlStatusInfoCodes> CODES_BY_STATUS = new HashMap<>();

    static {
        for (GqlStatusInfoCodes code : GqlStatusInfoCodes.values()) {
            CODES_BY_STATUS.putIfAbsent(code.getStatusString(), code);
        }
    }

    private ObfuscatedErrorDescriptions() {}

    static String of(ErrorGqlStatusObject status) {
        // LTS 5.26: ErrorGqlStatusObject has no obfuscatedStatusDescription() at 5.26.32, so the calendar line's first
        // line is gone and the standard description always stands in.
        return standardDescription(status.gqlStatus());
    }

    /** The condition and sub-condition of a GQLSTATUS code, as Neo4j words them, without any message. */
    static String standardDescription(String gqlStatus) {
        GqlStatusInfoCodes code = CODES_BY_STATUS.getOrDefault(gqlStatus, GqlStatusInfoCodes.STATUS_50N42);
        return Condition.createStandardDescription(code.getCondition(), code.getSubCondition());
    }
}
