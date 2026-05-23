package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SolrTransformerProvider} — verifies polyfill prepending,
 * solrConfigXmlFile parsing, bindings merge, and error handling.
 */
class SolrTransformerProviderTest {

    @TempDir
    Path tempDir;

    private static final String IDENTITY_SCRIPT = "(function(bindings) { return function(msg) { return msg; }; })";

    private Map<String, Object> providerConfig(String script, String bindingsJson) {
        var config = new LinkedHashMap<String, Object>();
        config.put("initializationScript", script);
        config.put("bindingsObject", bindingsJson);
        return config;
    }

    private Map<String, Object> providerConfigWithFile(Path scriptFile, String bindingsJson) {
        var config = new LinkedHashMap<String, Object>();
        config.put("initializationScriptFile", scriptFile.toString());
        config.put("bindingsObject", bindingsJson);
        return config;
    }

    @Test
    void createTransformer_withInlineScript() {
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfig(IDENTITY_SCRIPT, "{}"));
        assertNotNull(transformer);
    }

    @Test
    void createTransformer_withScriptFile() throws Exception {
        var scriptFile = tempDir.resolve("test.js");
        Files.writeString(scriptFile, IDENTITY_SCRIPT);
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfigWithFile(scriptFile, "{}"));
        assertNotNull(transformer);
    }

    @Test
    void createTransformer_prependsPolyfill_urlSearchParamsAvailable() {
        // Script that uses URLSearchParams — would fail without polyfill
        var script = "(function(bindings) { return function(msg) { " +
            "var p = new URLSearchParams('q=test&rows=10'); " +
            "msg.set('q', p.get('q')); " +
            "msg.set('rows', p.get('rows')); " +
            "p.set('df', 'title'); " +
            "msg.set('df', p.get('df')); " +
            "return msg; }; })";
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfig(script, "{}"));

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);
        assertEquals("test", result.get("q"));
        assertEquals("10", result.get("rows"));
        assertEquals("title", result.get("df"));
    }

    @Test
    void createTransformer_withSolrConfigXmlFile() throws Exception {
        var xmlFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                  <str name="rows">20</str>
                </lst>
              </requestHandler>
            </config>
            """);

        var script = "(function(bindings) { return function(msg) { " +
            "var sc = bindings.solrConfig; " +
            "msg.set('hasConfig', '' + (sc != null)); " +
            "return msg; }; })";

        var config = providerConfig(script, "{}");
        config.put("solrConfigXmlFile", xmlFile.toString());

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);
        assertEquals("true", result.get("hasConfig"));
    }

    @Test
    void createTransformer_xmlConfigMergedWithBindings() throws Exception {
        var xmlFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults"><str name="df">title</str></lst>
              </requestHandler>
            </config>
            """);

        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('customKey', bindings.customKey); " +
            "msg.set('hasConfig', '' + (bindings.solrConfig != null)); " +
            "return msg; }; })";

        var config = providerConfig(script, "{\"customKey\":\"customValue\"}");
        config.put("solrConfigXmlFile", xmlFile.toString());

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);
        assertEquals("customValue", result.get("customKey"));
        assertEquals("true", result.get("hasConfig"));
    }

    @Test
    void createTransformer_inlineSolrConfigInBindings() {
        var script = "(function(bindings) { return function(msg) { " +
            "var handler = bindings.solrConfig['/select']; " +
            "msg.set('df', handler.defaults.df); " +
            "return msg; }; })";

        var bindingsJson = "{\"solrConfig\":{\"/select\":{\"defaults\":{\"df\":\"content\"}}}}";
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfig(script, bindingsJson));

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);
        assertEquals("content", result.get("df"));
    }

    @Test
    void createTransformer_noSolrConfigXml_noError() {
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfig(IDENTITY_SCRIPT, "{}"));
        assertNotNull(transformer);
    }

    @Test
    void createTransformer_missingBindingsObject_succeeds() {
        var config = new LinkedHashMap<String, Object>();
        config.put("initializationScript", IDENTITY_SCRIPT);
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);
        assertNotNull(transformer);
    }

    @Test
    void createTransformer_objectBindings() {
        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('customKey', bindings.customKey); " +
            "return msg; }; })";

        var config = new LinkedHashMap<String, Object>();
        config.put("initializationScript", script);
        config.put("bindingsObject", Map.of("customKey", "from-object"));

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);
        assertEquals("from-object", result.get("customKey"));
    }

    @Test
    void createTransformer_nullConfig_throws() {
        var provider = new SolrTransformerProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(null));
    }
}
