/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.querylog.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.dozerdb.querylog.entry.AnnotationDataFormat;
import org.dozerdb.querylog.entry.DetailLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The configuration's derived facts: the query log is off, and a monitor event costs nothing more than reading it,
 * only when neither queries nor transactions are logged; each side is off on its own level, so a query event under
 * {@code OFF} returns at once however the transaction level is set, and a transaction event the other way round.
 */
class QueryLogConfigurationTest {

    @ParameterizedTest(name = "queries {0}, transactions {1} -> off: {2}, queries off: {3}, transactions off: {4}")
    @CsvSource({
        "OFF,     OFF,     true,  true,  true",
        "OFF,     INFO,    false, true,  false",
        "OFF,     VERBOSE, false, true,  false",
        "INFO,    OFF,     false, false, true",
        "INFO,    INFO,    false, false, false",
        "INFO,    VERBOSE, false, false, false",
        "VERBOSE, OFF,     false, false, true",
        "VERBOSE, INFO,    false, false, false",
        "VERBOSE, VERBOSE, false, false, false",
    })
    void eachSideIsOffOnItsOwnLevelAndTheLogOnlyWhenBothAre(
            DetailLevel queries, DetailLevel transactions, boolean off, boolean queriesOff, boolean transactionsOff) {
        QueryLogConfiguration configuration = new QueryLogConfiguration(
                queries,
                Duration.ZERO,
                transactions,
                Duration.ZERO,
                true,
                Integer.MAX_VALUE,
                false,
                false,
                false,
                false,
                AnnotationDataFormat.JSON);

        assertThat(configuration.off()).isEqualTo(off);
        assertThat(configuration.queriesOff()).isEqualTo(queriesOff);
        assertThat(configuration.transactionsOff()).isEqualTo(transactionsOff);
    }
}
