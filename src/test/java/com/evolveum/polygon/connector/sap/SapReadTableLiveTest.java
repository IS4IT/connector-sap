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

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live smoke test for the RFC_READ_TABLE table-read path. It requires a real SAP system and is
 * driven by a {@code test.properties} file on the test classpath (see {@code test.properties.example}).
 * <p>
 * When {@code test.properties} is not present the whole test is skipped (via JUnit assumptions), so it
 * stays green in CI and for anyone without a SAP system. {@code test.properties} is git-ignored.
 */
public class SapReadTableLiveTest {

    private static final Log LOG = Log.getLog(SapReadTableLiveTest.class);

    private static final String CONFIG_FILE = "test.properties";
    private static final int MAX_ROWS = 10;

    private static SapConfiguration configuration;
    private static SapConnector connector;
    private static boolean available;

    @BeforeAll
    static void setUp() throws Exception {
        Properties props = load(CONFIG_FILE);
        if (props == null) {
            LOG.info("{0} not found on the test classpath - skipping live SAP test", CONFIG_FILE);
            available = false;
            return;
        }
        configuration = buildConfiguration(props);
        connector = new SapConnector();
        // init() validates the configuration, opens the destination and builds the schema
        connector.init(configuration);
        available = true;
    }

    @AfterAll
    static void tearDown() {
        if (connector != null) {
            try {
                connector.dispose();
            } catch (Exception e) {
                LOG.warn("connector.dispose() failed: {0}", e);
            }
        }
    }

    @Test
    public void testConnection() {
        assumeTrue(available, CONFIG_FILE + " not found - skipping live SAP test");
        connector.test();
    }

    @Test
    public void schemaContainsConfiguredTableObjectClasses() {
        assumeTrue(available, CONFIG_FILE + " not found - skipping live SAP test");
        Schema schema = connector.schema();
        for (String alias : configuration.getTableAliases().values()) {
            assertNotNull(schema.findObjectClassInfo(alias),
                    "schema is missing object class for configured table alias '" + alias + "'");
        }
    }

    @Test
    public void searchReadsRowsForEachConfiguredTable() {
        assumeTrue(available, CONFIG_FILE + " not found - skipping live SAP test");
        for (String tableName : configuration.getTableAliases().keySet()) {
            String alias = configuration.getTableAliases().get(tableName);
            List<ConnectorObject> results = new ArrayList<>();
            ResultsHandler handler = connectorObject -> {
                results.add(connectorObject);
                return results.size() < MAX_ROWS;
            };
            // null filter = find all; the smoke test only asserts it runs without error
            connector.executeQuery(new ObjectClass(alias), null, handler, new OperationOptionsBuilder().build());
            LOG.info("table {0} (alias {1}): read {2} row(s)", tableName, alias, results.size());
        }
    }

    private static Properties load(String fileName) throws Exception {
        try (InputStream in = SapReadTableLiveTest.class.getClassLoader().getResourceAsStream(fileName)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    private static SapConfiguration buildConfiguration(Properties p) {
        SapConfiguration c = new SapConfiguration();
        if (p.containsKey("loadBalancing")) {
            c.setLoadBalancing(Boolean.parseBoolean(p.getProperty("loadBalancing")));
        }
        c.setHost(p.getProperty("host"));
        if (p.containsKey("port")) {
            c.setPort(p.getProperty("port"));
        }
        c.setUser(p.getProperty("user"));
        c.setPlainPassword(p.getProperty("password"));
        if (p.containsKey("logonGroup")) {
            c.setLogonGroup(p.getProperty("logonGroup"));
        }
        c.setSystemId(p.getProperty("r3name"));
        if (p.containsKey("systemNumber")) {
            c.setSystemNumber(p.getProperty("systemNumber"));
        }
        c.setClient(p.getProperty("client"));
        if (p.containsKey("lang")) {
            c.setLang(p.getProperty("lang"));
        }
        if (p.containsKey("sncMode")) {
            c.setSncMode(p.getProperty("sncMode"));
        }
        if (p.containsKey("sncMyName")) {
            c.setSncMyName(p.getProperty("sncMyName"));
        }
        if (p.containsKey("sncLibrary")) {
            c.setSncLibrary(p.getProperty("sncLibrary"));
        }
        if (p.containsKey("sncPartnerName")) {
            c.setSncPartnerName(p.getProperty("sncPartnerName"));
        }
        if (p.containsKey("sncQoP")) {
            c.setSncQoP(p.getProperty("sncQoP"));
        }
        if (p.containsKey("tableReadFunction")) {
            c.setTableReadFunction(p.getProperty("tableReadFunction"));
        }
        if (p.containsKey("tables")) {
            c.setTables(p.getProperty("tables").split(";"));
        }
        if (p.containsKey("tableParameterNames")) {
            c.setTableParameterNames(p.getProperty("tableParameterNames").split(";"));
        }
        return c;
    }
}
