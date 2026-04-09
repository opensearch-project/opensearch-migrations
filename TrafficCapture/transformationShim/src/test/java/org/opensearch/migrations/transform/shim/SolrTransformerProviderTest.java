package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SolrTransformerProvider — the ServiceLoader-discovered provider
 * that creates Solr JS transformers with polyfill and optional solrconfig.xml parsing.
 */
class SolrTransformerProviderTest {

    @TempDir
    Path tempDir;

    private final SolrTransformerProvider provider = new SolrTransformerProvider();

    private static final String IDENTITY_JS =
        "(function(bindings) { return function(msg) { return msg; }; })";

    @Test
    void getName_returnsSolrTransformerProvider() {
        assertEquals("SolrTransformerProvider", provider.getName());
    }

    @Test
    void createTransformer_scriptFile_prependsPolyfill() throws Exception {
        // Script uses URLSearchParams — only works if polyfill is prepended
        String script =
            "(function(bindings) {\n" +
            "  return function(msg) {\n" +
            "    var p = new URLSearchParams('q=test');\n" +
            "    msg.set('q', p.get('q'));\n" +
            "    return msg;\n" +
            "  };\n" +
            "})";
        Path jsFile = tempDir.resolve("test.js");
        Files.writeString(jsFile, script);

        var config = Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{}");
        var transformer = provider.createTransformer(config);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("test", result.get("q"));
    }

    @Test
    void createTransformer_inlineScript() {
        var config = Map.of(
            "initializationScript", IDENTITY_JS,
            "bindingsObject", "{}");
        var transformer = provider.createTransformer(config);
        assertNotNull(transformer);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("value", result.get("key"));
    }

    @Test
    void createTransformer_withSolrConfigXmlFile() throws Exception {
        String script =
            "(function(bindings) {\n" +
            "  return function(msg) {\n" +
            "    var sc = bindings.solrConfig;\n" +
            "    if (sc && sc['/select'] && sc['/select'].defaults) {\n" +
            "      msg.set('df', sc['/select'].defaults.df);\n" +
            "    }\n" +
            "    return msg;\n" +
            "  };\n" +
            "})";
        Path jsFile = tempDir.resolve("test.js");
        Files.writeString(jsFile, script);

        Path xmlFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">title</str>
                  <int name="rows">20</int>
                </lst>
              </requestHandler>
            </config>
            """);

        var config = Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{}",
            "solrConfigXmlFile", xmlFile.toAbsolutePath().toString());
        var transformer = provider.createTransformer(config);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("title", result.get("df"));
    }

    @Test
    void createTransformer_solrConfigXmlMergesWithExistingBindings() throws Exception {
        String script =
            "(function(bindings) {\n" +
            "  return function(msg) {\n" +
            "    msg.set('custom', bindings.customKey);\n" +
            "    msg.set('hasSolrConfig', bindings.solrConfig != null);\n" +
            "    return msg;\n" +
            "  };\n" +
            "})";
        Path jsFile = tempDir.resolve("test.js");
        Files.writeString(jsFile, script);

        Path xmlFile = tempDir.resolve("solrconfig.xml");
        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults"><str name="df">title</str></lst>
              </requestHandler>
            </config>
            """);

        var config = Map.of(
            "initializationScriptFile", jsFile.toAbsolutePath().toString(),
            "bindingsObject", "{\"customKey\":\"customValue\"}",
            "solrConfigXmlFile", xmlFile.toAbsolutePath().toString());
        var transformer = provider.createTransformer(config);
        var result = (Map<?, ?>) transformer.transformJson(new HashMap<>(Map.of("key", "value")));
        assertEquals("customValue", result.get("custom"));
        assertEquals(true, result.get("hasSolrConfig"));
    }

    @Test
    void createTransformer_missingBindings_throws() {
        var config = Map.of("initializationScript", IDENTITY_JS);
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
    }

    @Test
    void createTransformer_missingScript_throws() {
        var config = Map.of("bindingsObject", "{}");
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
    }

    @Test
    void createTransformer_nullConfig_throws() {
        assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(null));
    }
}
