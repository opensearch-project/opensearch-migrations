package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrConfigProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesDefaultsAndInvariants() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                  <int name="rows">20</int>
                  <str name="wt">json</str>
                </lst>
                <lst name="invariants">
                  <str name="facet.field">cat</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        @SuppressWarnings("unchecked")
        var select = (Map<String, Object>) result.get("/select");
        @SuppressWarnings("unchecked")
        var defaults = (Map<String, String>) select.get("defaults");
        @SuppressWarnings("unchecked")
        var invariants = (Map<String, String>) select.get("invariants");

        assertEquals("title", defaults.get("df"));
        assertEquals("20", defaults.get("rows"));
        assertEquals("json", defaults.get("wt"));
        assertEquals("cat", invariants.get("facet.field"));
    }

    @Test
    void skipsHandlersWithNoDefaultsOrInvariants() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/update" class="solr.UpdateRequestHandler"/>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">content</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        assertTrue(result.containsKey("/select"));
        assertTrue(!result.containsKey("/update"));
    }

    @Test
    void returnsEmptyForMissingFile() {
        var result = SolrConfigProvider.fromXmlFile(Path.of("/nonexistent/solrconfig.xml"));
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForNullPath() {
        var result = SolrConfigProvider.fromXmlFile(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForMalformedXml() throws Exception {
        Path xml = tempDir.resolve("bad.xml");
        Files.writeString(xml, "not xml at all");

        var result = SolrConfigProvider.fromXmlFile(xml);
        assertTrue(result.isEmpty());
    }

    @Test
    void parsesMultipleHandlers() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                </lst>
              </requestHandler>
              <requestHandler name="/admin/ping" class="solr.PingRequestHandler">
                <lst name="invariants">
                  <str name="q">*:*</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("/select"));
        assertTrue(result.containsKey("/admin/ping"));
    }

    @Test
    void parsesFloatAndBoolTypes() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <float name="hl.regex.slop">0.5</float>
                  <bool name="facet">true</bool>
                  <str name="df">title</str>
                  <int name="rows">10</int>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        @SuppressWarnings("unchecked")
        var defaults = (Map<String, String>) ((Map<String, Object>) result.get("/select")).get("defaults");
        assertEquals("0.5", defaults.get("hl.regex.slop"));
        assertEquals("true", defaults.get("facet"));
        assertEquals("title", defaults.get("df"));
        assertEquals("10", defaults.get("rows"));
    }

    @Test
    void handlerWithOnlyInvariants() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="invariants">
                  <str name="wt">json</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        @SuppressWarnings("unchecked")
        var select = (Map<String, Object>) result.get("/select");
        assertTrue(!select.containsKey("defaults"));
        @SuppressWarnings("unchecked")
        var invariants = (Map<String, String>) select.get("invariants");
        assertEquals("json", invariants.get("wt"));
    }

    @Test
    void parsesAppendsSection() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                </lst>
                <lst name="appends">
                  <str name="fq">inStock:true</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        @SuppressWarnings("unchecked")
        var select = (Map<String, Object>) result.get("/select");
        @SuppressWarnings("unchecked")
        var appends = (Map<String, String>) select.get("appends");
        assertEquals("inStock:true", appends.get("fq"));
    }

    @Test
    void unknownLstNamesAreSkipped() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                </lst>
                <lst name="components">
                  <str name="search">mySearchComponent</str>
                </lst>
                <lst name="custom-stuff">
                  <str name="foo">bar</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var result = SolrConfigProvider.fromXmlFile(xml);
        @SuppressWarnings("unchecked")
        var select = (Map<String, Object>) result.get("/select");
        assertEquals(1, select.size());
        assertTrue(select.containsKey("defaults"));
    }
}
