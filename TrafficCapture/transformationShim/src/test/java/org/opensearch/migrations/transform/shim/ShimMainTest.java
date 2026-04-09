package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.beust.jcommander.JCommander;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensearch.migrations.transform.IJsonTransformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that ShimMain correctly wires --transformerConfig through TransformationLoader
 * and ServiceLoader to instantiate SolrTransformerProvider-based transformers.
 */
class ShimMainTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal JS transform that sets a "transformed" flag on the message. */
    private static final String SIMPLE_TRANSFORM_JS =
        "(function(bindings) { return function(msg) { msg.set('transformed', true); return msg; }; })";

    /** Build a --transformerConfig JSON string for SolrTransformerProvider. */
    private static String buildConfig(Map<String, Object> providerConfig) {
        try {
            return MAPPER.writeValueAsString(new Object[]{Map.of("SolrTransformerProvider", providerConfig)});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- createTransformer via --transformerConfig (full provider pattern) ---

    @Test
    void createTransformer_withSolrTransformerProvider_inlineScript() {
        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = buildConfig(Map.of(
            "initializationScript", SIMPLE_TRANSFORM_JS,
            "bindingsObject", "{}"));

        var transformer = ShimMain.createTransformer(params);
        assertNotNull(transformer);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals(true, result.get("transformed"));
    }

    @Test
    void createTransformer_withSolrTransformerProvider_scriptFile() throws Exception {
        Path jsFile = tempDir.resolve("transform.js");
        Files.writeString(jsFile, SIMPLE_TRANSFORM_JS);

        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = buildConfig(Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{}"));

        var transformer = ShimMain.createTransformer(params);
        assertNotNull(transformer);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals(true, result.get("transformed"));
    }

    @Test
    void createTransformer_withSolrTransformerProvider_bindingsPassedToScript() throws Exception {
        // Script that reads bindings.testKey and sets it on the message
        String script =
            "(function(bindings) { return function(msg) { msg.set('fromBindings', bindings.testKey); return msg; }; })";
        Path jsFile = tempDir.resolve("bindings-test.js");
        Files.writeString(jsFile, script);

        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = buildConfig(Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{\"testKey\":\"hello\"}"));

        var transformer = ShimMain.createTransformer(params);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("hello", result.get("fromBindings"));
    }

    @Test
    void createTransformer_withSolrConfigXmlFile() throws Exception {
        // Script that reads bindings.solrConfig and sets it on the message
        String script =
            "(function(bindings) { return function(msg) {" +
            "  if (bindings.solrConfig) {" +
            "    var select = bindings.solrConfig['/select'];" +
            "    if (select && select.defaults) { msg.set('df', select.defaults.df); }" +
            "  }" +
            "  return msg;" +
            "}; })";
        Path jsFile = tempDir.resolve("solrconfig-test.js");
        Files.writeString(jsFile, script);

        Path xmlFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = buildConfig(Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{}",
            "solrConfigXmlFile", xmlFile.toAbsolutePath().toString()));

        var transformer = ShimMain.createTransformer(params);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("title", result.get("df"));
    }

    @Test
    void createTransformer_withUrlSearchParamsPolyfill() throws Exception {
        // Script that uses URLSearchParams — verifies polyfill is auto-prepended
        String script =
            "(function(bindings) { return function(msg) {" +
            "  var params = new URLSearchParams('q=hello&rows=10');" +
            "  msg.set('q', params.get('q'));" +
            "  msg.set('rows', params.get('rows'));" +
            "  return msg;" +
            "}; })";
        Path jsFile = tempDir.resolve("polyfill-test.js");
        Files.writeString(jsFile, script);

        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = buildConfig(Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{}"));

        var transformer = ShimMain.createTransformer(params);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("hello", result.get("q"));
        assertEquals("10", result.get("rows"));
    }

    @Test
    void createTransformer_returnsNullWhenNoConfig() {
        var params = new ShimMain.RequestTransformationParams();
        assertNull(ShimMain.createTransformer(params));
    }

    // --- JCommander CLI wiring tests ---

    @Test
    void jcommander_parsesTransformerConfig() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        String config = "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\",\"bindingsObject\":\"{}\"}}]";
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfig", config);
        assertEquals(config, params.requestTransformationParams.transformerConfig);
    }

    @Test
    void jcommander_parsesTransformerConfigKebab() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        String config = "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\",\"bindingsObject\":\"{}\"}}]";
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformer-config", config);
        assertEquals(config, params.requestTransformationParams.transformerConfig);
    }

    @Test
    void jcommander_parsesResponseTransformerConfig() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        String config = "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\",\"bindingsObject\":\"{}\"}}]";
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--responseTransformerConfig", config);
        assertEquals(config, params.responseTransformationParams.transformerConfig);
    }

    @Test
    void jcommander_parsesResponseTransformerConfigKebab() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        String config = "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\",\"bindingsObject\":\"{}\"}}]";
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--response-transformer-config", config);
        assertEquals(config, params.responseTransformationParams.transformerConfig);
    }

    @Test
    void jcommander_parsesTransformerConfigFile() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfigFile", "/path/to/config.json");
        assertEquals("/path/to/config.json", params.requestTransformationParams.transformerConfigFile);
    }

    @Test
    void jcommander_parsesTransformerConfigEncoded() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr", "--transformerConfigEncoded", "abc123");
        assertEquals("abc123", params.requestTransformationParams.transformerConfigEncoded);
    }

    @Test
    void jcommander_noTransformerConfig_leavesNull() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--primary", "solr");
        assertNull(params.requestTransformationParams.transformerConfig);
        assertNull(params.requestTransformationParams.transformerConfigEncoded);
        assertNull(params.requestTransformationParams.transformerConfigFile);
        assertNull(params.responseTransformationParams.transformerConfig);
    }

    @Test
    void jcommander_parsesTransformTarget() {
        var params = new ShimMain.Parameters();
        var jc = buildJCommander(params);
        jc.parse("--listenPort", "8080", "--target", "solr=http://localhost:8983",
            "--target", "os=http://localhost:9200",
            "--primary", "solr", "--transformTarget", "os");
        assertEquals("os", params.transformTarget);
    }

    private static JCommander buildJCommander(ShimMain.Parameters params) {
        return JCommander.newBuilder()
            .addObject(params)
            .addObject(params.requestTransformationParams)
            .addObject(params.responseTransformationParams)
            .build();
    }
}
