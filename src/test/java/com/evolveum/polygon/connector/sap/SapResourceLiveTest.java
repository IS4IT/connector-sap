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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live midPoint-side tests for the SAP connector.
 * <p>
 * Each test creates a concrete SAP resource that inherits the deploy-time resource template (so the
 * connection settings stay single-sourced in {@code test.properties} / the template) and adds its own
 * table settings, then asserts the resulting behaviour through midPoint's REST API: the object classes
 * that show up in the generated resource schema and the objects they return from SAP.
 * <p>
 * These exercise the whole stack (midPoint -> connector -> JCo -> SAP), so they need the {@code docker/}
 * test rig (see {@code docker/README.md}):
 * <ul>
 *   <li>a running midPoint with the SAP connector + JCo deployed and the resource template imported
 *       ({@code docker/deploy-connector.sh} does all of this), and</li>
 *   <li>that midPoint able to reach the SAP system configured in the template.</li>
 * </ul>
 * When midPoint is not reachable or the template is missing the tests are skipped (JUnit assumptions),
 * so the suite stays green without the rig.
 * <p>
 * The midPoint endpoint and credentials default to the docker rig and can be overridden in
 * {@code test.properties}: {@code midpoint.url}, {@code midpoint.user}, {@code midpoint.password},
 * {@code midpoint.templateOid}.
 */
public class SapResourceLiveTest {

    private static final Log LOG = Log.getLog(SapResourceLiveTest.class);

    private static final String NS_COMMON = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_ICFC = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3";
    private static final String NS_QUERY = "http://prism.evolveum.com/xml/ns/public/query-3";
    private static final String NS_RI = "http://midpoint.evolveum.com/xml/ns/public/resource/instance-3";
    private static final String NS_CFG =
            "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
                    + "com.evolveum.polygon.connector-sap/com.evolveum.polygon.connector.sap.SapConnector";

    private static final String DEFAULT_TEMPLATE_OID = "f698ab61-55f4-4eec-bba4-81da4b9f52d8";

    private static String restUrl;       // .../midpoint/ws/rest
    private static String authHeader;    // Basic ...
    private static String templateOid;
    private static boolean rigAvailable;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> createdResourceOids = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        Properties p = loadProperties("test.properties");
        String url = prop(p, "midpoint.url", "http://localhost:11080/midpoint");
        String user = prop(p, "midpoint.user", "administrator");
        String password = prop(p, "midpoint.password", "T3stPw890uio");
        templateOid = prop(p, "midpoint.templateOid", DEFAULT_TEMPLATE_OID);

        restUrl = url.replaceAll("/+$", "") + "/ws/rest";
        authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        rigAvailable = templateResourcePresent();
        if (!rigAvailable) {
            LOG.info("midPoint at {0} not reachable or template {1} missing - midPoint resource tests will be skipped",
                    restUrl, templateOid);
        }
    }

    @AfterEach
    void deleteCreatedResources() {
        for (String oid : createdResourceOids) {
            try {
                HttpResponse<String> r = send("DELETE", "/resources/" + oid, null, null);
                if (r.statusCode() != 204 && r.statusCode() != 200) {
                    LOG.warn("cleanup: deleting resource {0} returned HTTP {1}", oid, r.statusCode());
                }
            } catch (Exception e) {
                LOG.warn("cleanup: failed to delete resource {0}: {1}", oid, e);
            }
        }
        createdResourceOids.clear();
    }

    /**
     * A table defined as {@code AGR_TEXTS as AUDITROLES WHERE AGR_NAME LIKE 'SAP_AUDITOR%' AND SPRAS = 'D'
     * AND LINE = '00000'} must (a) produce an AUDITROLES object class in the generated resource schema and
     * (b) return only the rows matching that WHERE clause (SAP_AUDITOR* roles, German short-description line).
     */
    @Test
    public void auditRolesObjectTypeIsGeneratedAndFiltered() throws Exception {
        assumeTrue(rigAvailable, "midPoint test rig not available - skipping");

        String tables = "AGR_TEXTS as AUDITROLES WHERE AGR_NAME LIKE 'SAP_AUDITOR%' AND SPRAS = 'D' and LINE = '00000'";
        String oid = createTemplateBasedResource("zz-test-sap-auditroles", tables);
        testResource(oid);

        // (a) the AUDITROLES object type shows up in the generated resource schema
        TreeSet<String> objectClasses = generatedObjectClasses(oid);
        assertTrue(objectClasses.contains("CustomAUDITROLESObjectClass"),
                "resource schema must contain the AUDITROLES object class, got: " + objectClasses);

        // (b) querying it returns only the rows the WHERE clause selects
        List<String> names = searchObjectNames(oid, "ri:CustomAUDITROLESObjectClass");
        LOG.info("AUDITROLES returned {0} object(s)", names.size());
        assertFalse(names.isEmpty(),
                "expected at least one SAP_AUDITOR* role on the test system, but the query returned none "
                        + "(connection problem or the WHERE clause did not match)");
        for (String name : names) {
            assertTrue(name.startsWith("SAP_AUDITOR"),
                    "WHERE clause not applied: '" + name + "' does not match AGR_NAME LIKE 'SAP_AUDITOR%'");
            assertTrue(name.endsWith(":D:00000"),
                    "WHERE clause not applied: '" + name + "' is not the German (SPRAS=D) first line (LINE=00000)");
        }
    }

    /**
     * The same SAP table must be usable for several object types. Maps AGR_DEFINE twice, with different
     * WHERE clauses, and checks that both object classes are generated and that each applies its own
     * filter independently (the narrower one is a strict subset of the broader one).
     */
    @Test
    public void sameTableCanBackMultipleObjectTypes() throws Exception {
        assumeTrue(rigAvailable, "midPoint test rig not available - skipping");

        String oid = createTemplateBasedResource("zz-test-sap-multialias",
                "AGR_DEFINE as AUDITROLES WHERE AGR_NAME LIKE 'SAP_AUDITOR%'",
                "AGR_DEFINE as AUDITADMINROLES WHERE AGR_NAME LIKE 'SAP_AUDITOR_ADMIN%'");
        testResource(oid);

        // both object classes are generated from the same SAP table
        Set<String> objectClasses = generatedObjectClasses(oid);
        assertTrue(objectClasses.contains("CustomAUDITROLESObjectClass"),
                "missing AUDITROLES object class: " + objectClasses);
        assertTrue(objectClasses.contains("CustomAUDITADMINROLESObjectClass"),
                "the same SAP table did not yield a second, independent object class: " + objectClasses);

        List<String> auditRoles = searchObjectNames(oid, "ri:CustomAUDITROLESObjectClass");
        List<String> auditAdmin = searchObjectNames(oid, "ri:CustomAUDITADMINROLESObjectClass");
        LOG.info("same table AGR_DEFINE -> AUDITROLES={0}, AUDITADMINROLES={1}", auditRoles.size(), auditAdmin.size());

        // each object type applies its own WHERE
        assertFalse(auditRoles.isEmpty(), "expected SAP_AUDITOR* roles on the test system");
        assertFalse(auditAdmin.isEmpty(), "expected SAP_AUDITOR_ADMIN* roles on the test system");
        for (String name : auditRoles) {
            assertTrue(name.startsWith("SAP_AUDITOR"), "AUDITROLES WHERE not applied: " + name);
        }
        for (String name : auditAdmin) {
            assertTrue(name.startsWith("SAP_AUDITOR_ADMIN"), "AUDITADMINROLES WHERE not applied: " + name);
        }
        // the two filters are independent: the narrower object type is a strict subset of the broader one
        assertTrue(new TreeSet<>(auditRoles).containsAll(auditAdmin),
                "the narrower object type must be a subset of the broader one (both read AGR_DEFINE)");
        assertTrue(auditRoles.size() > auditAdmin.size(),
                "the two WHERE clauses on the same table must select different counts (got "
                        + auditRoles.size() + " and " + auditAdmin.size() + ")");
    }

    // --- resource lifecycle helpers --------------------------------------------------------------

    /**
     * Creates a concrete resource that inherits the template (connection + defaults) and adds the given
     * {@code tables} definitions. Returns the new OID and registers it for cleanup.
     */
    private String createTemplateBasedResource(String name, String... tablesDefs) throws Exception {
        String oid = UUID.randomUUID().toString();
        StringBuilder cfg = new StringBuilder();
        for (String t : tablesDefs) {
            cfg.append("            <cfg:tables>").append(xmlText(t)).append("</cfg:tables>\n");
        }
        String body = "<resource xmlns=\"" + NS_COMMON + "\" xmlns:c=\"" + NS_COMMON + "\" oid=\"" + oid + "\">\n"
                + "    <name>" + xmlText(name + "-" + oid.substring(0, 8)) + "</name>\n"
                + "    <super><resourceRef oid=\"" + templateOid + "\"/></super>\n"
                + "    <connectorConfiguration xmlns:icfc=\"" + NS_ICFC + "\">\n"
                + "        <icfc:configurationProperties xmlns:cfg=\"" + NS_CFG + "\">\n"
                + cfg
                + "        </icfc:configurationProperties>\n"
                + "    </connectorConfiguration>\n"
                + "</resource>\n";

        HttpResponse<String> r = send("POST", "/resources", body, "application/xml");
        assertEquals(201, r.statusCode(), "creating resource failed: HTTP " + r.statusCode() + " - " + r.body());
        createdResourceOids.add(oid);
        return oid;
    }

    /** Runs the resource test connection (which also generates and stores the schema) and asserts success. */
    private void testResource(String oid) throws Exception {
        HttpResponse<String> r = send("POST", "/resources/" + oid + "/test", null, null);
        assertEquals(200, r.statusCode(), "resource test connection failed: HTTP " + r.statusCode() + " - " + r.body());
        String status = xpathString(parse(r.body()), "/*/*[local-name()='status'][1]");
        assertEquals("success", status, "resource test connection did not succeed: " + r.body());
    }

    /** Object class names (xsd complexType names) in the resource's generated schema. */
    private TreeSet<String> generatedObjectClasses(String oid) throws Exception {
        HttpResponse<String> r = send("GET", "/resources/" + oid, null, null);
        assertEquals(200, r.statusCode(), "fetching resource failed: HTTP " + r.statusCode());
        NodeList names = xpathNodes(parse(r.body()), "//*[local-name()='complexType']/@name");
        TreeSet<String> result = new TreeSet<>();
        for (int i = 0; i < names.getLength(); i++) {
            result.add(names.item(i).getNodeValue());
        }
        return result;
    }

    /** Searches the resource for the given object class (live, on SAP) and returns the shadow names. */
    private List<String> searchObjectNames(String resourceOid, String objectClassQName) throws Exception {
        String query = "<query xmlns=\"" + NS_QUERY + "\" xmlns:c=\"" + NS_COMMON + "\" xmlns:ri=\"" + NS_RI + "\">\n"
                + "  <filter>\n"
                + "    <and>\n"
                + "      <ref><path>resourceRef</path><value oid=\"" + resourceOid + "\"/></ref>\n"
                + "      <equal><path>objectClass</path><value>" + objectClassQName + "</value></equal>\n"
                + "    </and>\n"
                + "  </filter>\n"
                + "</query>\n";
        HttpResponse<String> r = send("POST", "/shadows/search", query, "application/xml");
        assertEquals(200, r.statusCode(), "shadow search failed: HTTP " + r.statusCode() + " - " + r.body());
        NodeList nameNodes = xpathNodes(parse(r.body()), "//*[local-name()='object']/*[local-name()='name']/text()");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nameNodes.getLength(); i++) {
            names.add(nameNodes.item(i).getNodeValue());
        }
        return names;
    }

    // --- low-level REST + XML helpers ------------------------------------------------------------

    private static boolean templateResourcePresent() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(restUrl + "/resources/" + templateOid))
                            .header("Authorization", authHeader)
                            .header("Accept", "application/xml")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(restUrl + path))
                .header("Authorization", authHeader)
                .header("Accept", "application/xml");
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String xpathString(Document doc, String expr) throws Exception {
        return (String) XPathFactory.newInstance().newXPath().evaluate(expr, doc, XPathConstants.STRING);
    }

    private static NodeList xpathNodes(Document doc, String expr) throws Exception {
        return (NodeList) XPathFactory.newInstance().newXPath().evaluate(expr, doc, XPathConstants.NODESET);
    }

    private static String xmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Properties loadProperties(String fileName) {
        try (InputStream in = SapResourceLiveTest.class.getClassLoader().getResourceAsStream(fileName)) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String prop(Properties p, String key, String def) {
        if (p == null) {
            return def;
        }
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
