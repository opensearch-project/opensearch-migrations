package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensearch.migrations.transform.TransformerConfigUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShimMainTest {

    @TempDir
    Path tempDir;

    private static final String SOLR_CONFIG_JSON =
        "{\"solrConfig\":{\"/select\":{\"defaults\":{\"df\":\"title\"}}}}";

    // --- resolveTransformBindings tests ---

    @Test
    void resolveBindings_transformerConfig_inlineJson() {
        var p = new ShimMain.Parameters();
        p.transformerConfig = SOLR_CONFIG_JSON;
        var bindings = ShimMain.resolveTransformBindings(p);
        assertSolrConfigPresent(bindings);
    }

    @Test
    void resolveBindings_transformerConfigEncoded_base64() {
        var p = new ShimMain.Parameters();
        p.transformerConfigEncoded = Base64.getEncoder().encodeToString(SOLR_CONFIG_JSON.getBytes());
        var bindings = ShimMain.resolveTransformBindings(p);
        assertSolrConfigPresent(bindings);
    }

    @Test
    void resolveBindings_transformerConfigFile_jsonFile() throws Exception {
        Path json = tempDir.resolve("config.json");
        Files.writeString(json, SOLR_CONFIG_JSON);

        var p = new ShimMain.Parameters();
        p.transformerConfigFile = json.toString();
        var bindings = ShimMain.resolveTransformBindings(p);
        assertSolrConfigPresent(bindings);
    }

    @Test
    void resolveBindings_transformerConfigFile_xmlFile() throws Exception {
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

        var p = new ShimMain.Parameters();
        p.transformerConfigFile = xml.toString();
        var bindings = ShimMain.resolveTransformBindings(p);
        assertSolrConfigPresent(bindings);
    }

    @Test
    void resolveBindings_returnsEmptyWhenNoneProvided() {
        var bindings = ShimMain.resolveTransformBindings(new ShimMain.Parameters());
        assertTrue(bindings.isEmpty());
    }

    @Test
    void resolveBindings_throwsWhenMultipleProvided() {
        var p = new ShimMain.Parameters();
        p.transformerConfig = SOLR_CONFIG_JSON;
        p.transformerConfigEncoded = Base64.getEncoder().encodeToString(SOLR_CONFIG_JSON.getBytes());
        assertThrows(TransformerConfigUtils.TooManyTransformationConfigSourcesException.class,
            () -> ShimMain.resolveTransformBindings(p));
    }

    @Test
    void resolveBindings_throwsOnInvalidJson() {
        var p = new ShimMain.Parameters();
        p.transformerConfig = "not valid json";
        assertThrows(ParameterException.class, () -> ShimMain.resolveTransformBindings(p));
    }

    @Test
    void resolveBindings_throwsOnMissingJsonFile() {
        var p = new ShimMain.Parameters();
        p.transformerConfigFile = "/nonexistent/config.json";
        assertThrows(TransformerConfigUtils.UnableToReadTransformationConfigException.class,
            () -> ShimMain.resolveTransformBindings(p));
    }

    // --- JCommander CLI param wiring tests ---

    @Test
    void jcommander_parsesTransformerConfig() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfig", SOLR_CONFIG_JSON);
        assertEquals(SOLR_CONFIG_JSON, params.transformerConfig);
    }

    @Test
    void jcommander_parsesTransformerConfigKebab() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformer-config", SOLR_CONFIG_JSON);
        assertEquals(SOLR_CONFIG_JSON, params.transformerConfig);
    }

    @Test
    void jcommander_parsesTransformerConfigEncoded() {
        var encoded = Base64.getEncoder().encodeToString(SOLR_CONFIG_JSON.getBytes());
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfigEncoded", encoded);
        assertEquals(encoded, params.transformerConfigEncoded);
    }

    @Test
    void jcommander_parsesTransformerConfigEncodedKebab() {
        var encoded = Base64.getEncoder().encodeToString(SOLR_CONFIG_JSON.getBytes());
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformer-config-encoded", encoded);
        assertEquals(encoded, params.transformerConfigEncoded);
    }

    @Test
    void jcommander_parsesTransformerConfigFile() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfigFile", "/path/to/config.json");
        assertEquals("/path/to/config.json", params.transformerConfigFile);
    }

    @Test
    void jcommander_parsesTransformerConfigFileKebab() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformer-config-file", "/path/to/config.json");
        assertEquals("/path/to/config.json", params.transformerConfigFile);
    }

    @Test
    void jcommander_noTransformerConfig_leavesNull() {
        var params = new ShimMain.Parameters();
        var jc = com.beust.jcommander.JCommander.newBuilder().addObject(params).build();
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr");
        assertNull(params.transformerConfig);
        assertNull(params.transformerConfigEncoded);
        assertNull(params.transformerConfigFile);
    }

    @SuppressWarnings("unchecked")
    private void assertSolrConfigPresent(Map<String, Object> bindings) {
        assertTrue(bindings.containsKey("solrConfig"));
        var solrConfig = (Map<String, Object>) bindings.get("solrConfig");
        assertNotNull(solrConfig.get("/select"));
    }
}
