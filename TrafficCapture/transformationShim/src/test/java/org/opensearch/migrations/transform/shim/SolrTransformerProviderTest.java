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
    void createTransformer_missingBindingsObject_throws() {
        var config = new LinkedHashMap<String, Object>();
        config.put("initializationScript", IDENTITY_SCRIPT);
        var provider = new SolrTransformerProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
    }

    @Test
    void createTransformer_nullConfig_throws() {
        var provider = new SolrTransformerProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(null));
    }

    // ─── solrSchemaXmlFile tests ──────────────────────────────────────────────

    @Test
    void createTransformer_withSolrSchemaXmlFile_populatesFieldTypesBinding() throws Exception {
        var schemaFile = tempDir.resolve("managed-schema.xml");
        Files.writeString(schemaFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema name="test" version="1.6">
              <fieldType name="string"       class="solr.StrField"/>
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="id"    type="string"/>
              <field name="title" type="text_general"/>
            </schema>
            """);

        // Script reads bindings.fieldTypes and exposes the class values for assertion
        var script = "(function(bindings) { return function(msg) { " +
            "var ft = bindings.fieldTypes; " +
            "msg.set('hasFieldTypes', '' + (ft != null)); " +
            "msg.set('idClass',    ft['id']); " +
            "msg.set('titleClass', ft['title']); " +
            "return msg; }; })";

        var config = providerConfig(script, "{}");
        config.put("solrSchemaXmlFile", schemaFile.toString());

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        assertEquals("true",             result.get("hasFieldTypes"));
        assertEquals("solr.StrField",    result.get("idClass"));
        assertEquals("solr.TextField",   result.get("titleClass"));
    }

    @Test
    void createTransformer_schemaXmlFileMergedWithOtherBindings() throws Exception {
        var schemaFile = tempDir.resolve("managed-schema.xml");
        Files.writeString(schemaFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema name="test" version="1.6">
              <fieldType name="string" class="solr.StrField"/>
              <field name="status" type="string"/>
            </schema>
            """);

        // Both customKey (from bindingsObject) and fieldTypes (from schema) should be present
        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('customKey',   bindings.customKey); " +
            "msg.set('statusClass', bindings.fieldTypes['status']); " +
            "return msg; }; })";

        var config = providerConfig(script, "{\"customKey\":\"myValue\"}");
        config.put("solrSchemaXmlFile", schemaFile.toString());

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        assertEquals("myValue",        result.get("customKey"));
        assertEquals("solr.StrField",  result.get("statusClass"));
    }

    @Test
    void createTransformer_schemaXmlFileAndSolrConfigXmlFileBothLoaded() throws Exception {
        var schemaFile = tempDir.resolve("managed-schema.xml");
        Files.writeString(schemaFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema name="test" version="1.6">
              <fieldType name="string" class="solr.StrField"/>
              <field name="id" type="string"/>
            </schema>
            """);

        var configFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(configFile, """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults"><str name="df">title</str></lst>
              </requestHandler>
            </config>
            """);

        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('hasSolrConfig',  '' + (bindings.solrConfig != null)); " +
            "msg.set('hasFieldTypes',  '' + (bindings.fieldTypes != null)); " +
            "msg.set('idClass',        bindings.fieldTypes['id']); " +
            "return msg; }; })";

        var config = providerConfig(script, "{}");
        config.put("solrConfigXmlFile",  configFile.toString());
        config.put("solrSchemaXmlFile",  schemaFile.toString());

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        assertEquals("true",           result.get("hasSolrConfig"));
        assertEquals("true",           result.get("hasFieldTypes"));
        assertEquals("solr.StrField",  result.get("idClass"));
    }

    @Test
    void createTransformer_missingSchemaXmlFile_noFieldTypesBinding() throws Exception {
        // When solrSchemaXmlFile points to a non-existent file, fieldTypes is absent
        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('hasFieldTypes', '' + (bindings.fieldTypes != null)); " +
            "return msg; }; })";

        var config = providerConfig(script, "{}");
        config.put("solrSchemaXmlFile", "/nonexistent/managed-schema.xml");

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        // Missing file → SolrSchemaProvider returns empty map → not put into bindings
        assertEquals("false", result.get("hasFieldTypes"));
    }

    @Test
    void createTransformer_blankSchemaXmlFile_noFieldTypesBinding() throws Exception {
        // Blank string for solrSchemaXmlFile is ignored
        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('hasFieldTypes', '' + (bindings.fieldTypes != null)); " +
            "return msg; }; })";

        var config = providerConfig(script, "{}");
        config.put("solrSchemaXmlFile", "   ");

        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(config);

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        assertEquals("false", result.get("hasFieldTypes"));
    }

    @Test
    void createTransformer_inlineFieldTypesInBindings_worksWithoutSchemaFile() {
        // Operator can provide fieldTypes directly in bindingsObject — no file needed
        var script = "(function(bindings) { return function(msg) { " +
            "msg.set('statusClass', bindings.fieldTypes['status']); " +
            "return msg; }; })";

        var bindingsJson = "{\"fieldTypes\":{\"status\":\"solr.StrField\",\"title\":\"solr.TextField\"}}";
        var provider = new SolrTransformerProvider();
        var transformer = provider.createTransformer(providerConfig(script, bindingsJson));

        var input = new LinkedHashMap<String, Object>();
        input.put("URI", "/test");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) transformer.transformJson(input);

        assertEquals("solr.StrField", result.get("statusClass"));
    }
}
