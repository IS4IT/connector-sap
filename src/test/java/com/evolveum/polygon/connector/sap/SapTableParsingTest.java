/*
 * Copyright (c) 2010-2016 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.polygon.connector.sap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the lenient RFC_READ_TABLE table-definition parser. These do not require a SAP
 * connection - they exercise {@link SapConfiguration#parseReadTableDefinitions()} directly.
 */
public class SapTableParsingTest {

    private SapConfiguration parse(String... tables) {
        SapConfiguration config = new SapConfiguration();
        config.setTableReadFunction(SapConfiguration.FN_READ_TABLE);
        config.setTables(tables);
        config.parseReadTableDefinitions();
        return config;
    }

    @Test
    public void testReadTableModeFlag() {
        SapConfiguration config = new SapConfiguration();
        assertFalse(config.isReadTableMode(), "default is the legacy RFC_GET_TABLE_ENTRIES path");

        config.setTableReadFunction(SapConfiguration.FN_READ_TABLE);
        assertTrue(config.isReadTableMode());

        config.setTableReadFunction(SapConfiguration.FN_BBP_READ_TABLE);
        assertTrue(config.isReadTableMode());

        config.setTableReadFunction("Z_MY_READ_TABLE");
        assertTrue(config.isReadTableMode(), "a custom Z-FM also enables the read-table path");

        config.setTableReadFunction(SapConfiguration.FN_GET_TABLE_ENTRIES);
        assertFalse(config.isReadTableMode());
    }

    @Test
    public void testBareTableName() {
        SapConfiguration config = parse("AGR_DEFINE");
        assertEquals("AGR_DEFINE", config.getTableAliases().get("AGR_DEFINE"), "alias defaults to table name");
        assertEquals(Collections.emptyList(), config.getTableKeys().get("AGR_DEFINE"), "no key override -> DDIC keys");
        assertEquals(Collections.emptyList(), config.getTableIgnores().get("AGR_DEFINE"));
        assertNull(config.getTableWhere().get("AGR_DEFINE"));
    }

    @Test
    public void testAliasOnly() {
        SapConfiguration config = parse("AGR_DEFINE as ACTIVITYGROUP");
        assertEquals("ACTIVITYGROUP", config.getTableAliases().get("AGR_DEFINE"));
        assertEquals(Collections.emptyList(), config.getTableKeys().get("AGR_DEFINE"));
    }

    @Test
    public void testLegacyLineStillParses() {
        SapConfiguration config = parse("AGR_DEFINE as ACTIVITYGROUP=MANDT:3:IGNORE,AGR_NAME:30:KEY,PARENT_AGR:30");
        assertEquals("ACTIVITYGROUP", config.getTableAliases().get("AGR_DEFINE"));
        assertEquals(Collections.singletonList("AGR_NAME"), config.getTableKeys().get("AGR_DEFINE"),
                ":KEY becomes a key override, lengths are ignored");
        assertEquals(Collections.singletonList("MANDT"), config.getTableIgnores().get("AGR_DEFINE"));
        assertNull(config.getTableWhere().get("AGR_DEFINE"));
    }

    @Test
    public void testKeyOverrideWithoutLength() {
        SapConfiguration config = parse("AGR_DEFINE=AGR_NAME:KEY");
        assertEquals(Collections.singletonList("AGR_NAME"), config.getTableKeys().get("AGR_DEFINE"));
    }

    @Test
    public void testWhereClauseWithEqualsSign() {
        SapConfiguration config = parse("AGR_DEFINE as ACTIVITYGROUP WHERE PARENT_AGR <> '' AND SPRAS = 'E'");
        assertEquals("ACTIVITYGROUP", config.getTableAliases().get("AGR_DEFINE"), "WHERE must not break alias parsing");
        assertEquals("PARENT_AGR <> '' AND SPRAS = 'E'", config.getTableWhere().get("AGR_DEFINE"),
                "the '=' inside the clause must stay in the WHERE, not split the table definition");
        assertEquals(Collections.emptyList(), config.getTableKeys().get("AGR_DEFINE"));
    }

    @Test
    public void testColumnsAndWhereCombined() {
        SapConfiguration config = parse("AGR_DEFINE=AGR_NAME:30:KEY WHERE PARENT_AGR <> ''");
        assertEquals(Collections.singletonList("AGR_NAME"), config.getTableKeys().get("AGR_DEFINE"));
        assertEquals("PARENT_AGR <> ''", config.getTableWhere().get("AGR_DEFINE"));
    }

    @Test
    public void testLowerCaseWhereKeyword() {
        SapConfiguration config = parse("AGR_DEFINE as ACTIVITYGROUP where PARENT_AGR <> ''");
        assertEquals("PARENT_AGR <> ''", config.getTableWhere().get("AGR_DEFINE"));
    }

    @Test
    public void testMultipleTables() {
        SapConfiguration config = parse("AGR_DEFINE as ACTIVITYGROUP", "USGRP as GROUP=USERGROUP:KEY");
        assertEquals(new LinkedHashSet<>(Arrays.asList("AGR_DEFINE", "USGRP")), config.getTableAliases().keySet());
        assertEquals(Collections.singletonList("USERGROUP"), config.getTableKeys().get("USGRP"));
    }
}
