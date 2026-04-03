package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShimMainTest {

    @TempDir
    Path tempDir;

    private static ShimMain.Parameters paramsWithPath(String path) {
        var p = new ShimMain.Parameters();
        p.solrConfigPath = path;
        return p;
    }

    private static ShimMain.Parameters paramsWithInline(String json) {
        var p = new ShimMain.Parameters();
        p.solrConfigInline = json;
        return p;
    }

    @Test
    void buildTransformBindings_xmlPath() throws Exception {
        Path xml = tempDir.resolve("solrconfig.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var bindings = ShimMain.buildTransformBindings(paramsWithPath(xml.toString()));
        assertTrue(bindings.containsKey("solrConfig"));
        @SuppressWarnings("unchecked")
        var solrConfig = (Map<String, Object>) bindings.get("solrConfig");
        assertNotNull(solrConfig.get("/select"));
    }

    @Test
    void buildTransformBindings_jsonFilePath() throws Exception {
        Path json = tempDir.resolve("solrconfig.json");
        Files.writeString(json, """
            {"solrConfig":{"/select":{"defaults":{"df":"content"}}}}
            """);

        var bindings = ShimMain.buildTransformBindings(paramsWithPath(json.toString()));
        assertTrue(bindings.containsKey("solrConfig"));
        @SuppressWarnings("unchecked")
        var solrConfig = (Map<String, Object>) bindings.get("solrConfig");
        assertNotNull(solrConfig.get("/select"));
    }

    @Test
    void buildTransformBindings_inlineJson() {
        var bindings = ShimMain.buildTransformBindings(paramsWithInline(
            "{\"solrConfig\":{\"/select\":{\"defaults\":{\"df\":\"title\"}}}}"));
        assertTrue(bindings.containsKey("solrConfig"));
        @SuppressWarnings("unchecked")
        var solrConfig = (Map<String, Object>) bindings.get("solrConfig");
        assertNotNull(solrConfig.get("/select"));
    }

    @Test
    void buildTransformBindings_returnsEmptyWhenNeitherProvided() {
        var bindings = ShimMain.buildTransformBindings(new ShimMain.Parameters());
        assertTrue(bindings.isEmpty());
    }

    @Test
    void buildTransformBindings_throwsWhenBothProvided() {
        var p = new ShimMain.Parameters();
        p.solrConfigPath = "/some/path.xml";
        p.solrConfigInline = "{\"solrConfig\":{}}";
        assertThrows(ParameterException.class, () -> ShimMain.buildTransformBindings(p));
    }

    @Test
    void buildTransformBindings_throwsOnInvalidInlineJson() {
        assertThrows(ParameterException.class,
            () -> ShimMain.buildTransformBindings(paramsWithInline("not valid json")));
    }

    @Test
    void buildTransformBindings_throwsOnMissingJsonFile() {
        assertThrows(ParameterException.class,
            () -> ShimMain.buildTransformBindings(paramsWithPath("/nonexistent/config.json")));
    }

    // --- JCommander CLI param wiring tests ---

    @Test
    void jcommander_parsesSolrConfigPath() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--solr-config-path", "/path/to/solrconfig.xml");
        assertEquals("/path/to/solrconfig.xml", params.solrConfigPath);
    }

    @Test
    void jcommander_parsesSolrConfigPathCamelCase() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--solrConfigPath", "/path/to/solrconfig.xml");
        assertEquals("/path/to/solrconfig.xml", params.solrConfigPath);
    }

    @Test
    void jcommander_parsesSolrConfigInline() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--solr-config-inline", "{\"solrConfig\":{}}");
        assertEquals("{\"solrConfig\":{}}", params.solrConfigInline);
    }

    @Test
    void jcommander_parsesSolrConfigInlineCamelCase() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--solrConfigInline", "{\"solrConfig\":{}}");
        assertEquals("{\"solrConfig\":{}}", params.solrConfigInline);
    }

    @Test
    void jcommander_neitherSolrConfigParam_leavesNull() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr");
        assertNull(params.solrConfigPath);
        assertNull(params.solrConfigInline);
    }
}
