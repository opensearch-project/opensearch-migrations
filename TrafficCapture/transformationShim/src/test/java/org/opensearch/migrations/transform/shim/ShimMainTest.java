package org.opensearch.migrations.transform.shim;

import com.beust.jcommander.JCommander;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ShimMain CLI parameter wiring — verifies JCommander binds
 * --transformerConfig, --responseTransformerConfig, --transformTarget
 * and their kebab/camelCase variants correctly, and that the
 * TransformerParams classes expose the right values via @Getter.
 */
class ShimMainTest {

    private static ShimMain.Parameters parse(String... args) {
        var params = new ShimMain.Parameters();
        JCommander.newBuilder()
            .addObject(params)
            .addObject(params.requestTransformationParams)
            .addObject(params.responseTransformationParams)
            .addObject(params.reportingParams)
            .build()
            .parse(args);
        return params;
    }

    // --- Request transformer config ---

    @Test
    void transformerConfig_kebabCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformer-config", "[{\"SolrTransformerProvider\":{}}]");
        assertEquals("[{\"SolrTransformerProvider\":{}}]", p.requestTransformationParams.getTransformerConfig());
    }

    @Test
    void transformerConfig_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformerConfig", "[{\"SolrTransformerProvider\":{}}]");
        assertEquals("[{\"SolrTransformerProvider\":{}}]", p.requestTransformationParams.getTransformerConfig());
    }

    @Test
    void transformerConfigFile_kebabCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformer-config-file", "/path/to/config.json");
        assertEquals("/path/to/config.json", p.requestTransformationParams.getTransformerConfigFile());
    }

    @Test
    void transformerConfigFile_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformerConfigFile", "/path/to/config.json");
        assertEquals("/path/to/config.json", p.requestTransformationParams.getTransformerConfigFile());
    }

    @Test
    void transformerConfigEncoded_kebabCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformer-config-encoded", "base64data");
        assertEquals("base64data", p.requestTransformationParams.getTransformerConfigEncoded());
    }

    @Test
    void transformerConfigEncoded_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformerConfigEncoded", "base64data");
        assertEquals("base64data", p.requestTransformationParams.getTransformerConfigEncoded());
    }

    // --- Response transformer config ---

    @Test
    void responseTransformerConfig_kebabCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--response-transformer-config", "[{}]");
        assertEquals("[{}]", p.responseTransformationParams.getTransformerConfig());
    }

    @Test
    void responseTransformerConfig_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--responseTransformerConfig", "[{}]");
        assertEquals("[{}]", p.responseTransformationParams.getTransformerConfig());
    }

    @Test
    void responseTransformerConfigFile_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--responseTransformerConfigFile", "/path/to/resp.json");
        assertEquals("/path/to/resp.json", p.responseTransformationParams.getTransformerConfigFile());
    }

    @Test
    void responseTransformerConfigEncoded_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--responseTransformerConfigEncoded", "base64resp");
        assertEquals("base64resp", p.responseTransformationParams.getTransformerConfigEncoded());
    }

    // --- Transform target ---

    @Test
    void transformTarget_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--transformTarget", "os");
        assertEquals("os", p.transformTarget);
    }

    @Test
    void transformTarget_defaultsToNull() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertNull(p.transformTarget);
    }

    // --- TransformerParams prefix ---

    @Test
    void requestTransformationParams_hasEmptyPrefix() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertEquals("", p.requestTransformationParams.getTransformerConfigParameterArgPrefix());
    }

    @Test
    void responseTransformationParams_hasResponsePrefix() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertEquals("response-", p.responseTransformationParams.getTransformerConfigParameterArgPrefix());
    }

    // --- No config defaults ---

    @Test
    void noTransformerConfig_allNull() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertNull(p.requestTransformationParams.getTransformerConfig());
        assertNull(p.requestTransformationParams.getTransformerConfigFile());
        assertNull(p.requestTransformationParams.getTransformerConfigEncoded());
        assertNull(p.responseTransformationParams.getTransformerConfig());
        assertNull(p.responseTransformationParams.getTransformerConfigFile());
        assertNull(p.responseTransformationParams.getTransformerConfigEncoded());
    }

    // --- watchTransforms flag ---

    @Test
    void watchTransforms_defaultsFalse() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertEquals(false, p.watchTransforms);
    }

    @Test
    void watchTransforms_setTrue() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--watchTransforms");
        assertEquals(true, p.watchTransforms);
    }

    // --- extractScriptFilePaths ---

    @Test
    void extractScriptFilePaths_singleProvider() {
        var paths = ShimMain.extractScriptFilePaths(
            "[{\"SolrTransformerProvider\":{\"initializationScriptFile\":\"/transforms/request.js\",\"bindingsObject\":\"{}\"}}]");
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).toString().endsWith("request.js"));
    }

    @Test
    void extractScriptFilePaths_multipleProviders() {
        var paths = ShimMain.extractScriptFilePaths(
            "[{\"SolrTransformerProvider\":{\"initializationScriptFile\":\"/a/req.js\",\"bindingsObject\":\"{}\"}},"
            + "{\"SolrTransformerProvider\":{\"initializationScriptFile\":\"/b/resp.js\",\"bindingsObject\":\"{}\"}}]");
        assertEquals(2, paths.size());
    }

    @Test
    void extractScriptFilePaths_noScriptFile() {
        var paths = ShimMain.extractScriptFilePaths(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"(function(){return function(m){return m;}})\",\"bindingsObject\":\"{}\"}}]");
        assertEquals(0, paths.size());
    }

    @Test
    void extractScriptFilePaths_invalidJson_returnsEmpty() {
        var paths = ShimMain.extractScriptFilePaths("not valid json");
        assertEquals(0, paths.size());
    }

    // --- extractBindings ---

    @Test
    void extractBindings_emptyBindingsObject_returnsEmpty() {
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\",\"bindingsObject\":\"{}\"}}]");
        assertTrue(bindings.isEmpty());
    }

    @Test
    void extractBindings_withSolrConfigInBindings_extractsIt() {
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{\\\"solrConfig\\\":{\\\"/select\\\":{\\\"defaults\\\":{\\\"df\\\":\\\"title\\\"}}}}\"}}]");
        assertTrue(bindings.containsKey("solrConfig"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractBindings_withFieldTypesInBindingsObject_extractsIt() {
        // Operator provides fieldTypes inline in bindingsObject JSON — no schema file needed.
        // This is the path used by the traffic replayer (JsonJSTransformerProvider) and
        // any deployment where the schema file is not accessible.
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{\\\"fieldTypes\\\":{\\\"id\\\":\\\"solr.StrField\\\"," +
            "\\\"title\\\":\\\"solr.TextField\\\"}}\"}}]");

        assertTrue(bindings.containsKey("fieldTypes"));
        var fieldTypes = (java.util.Map<String, String>) bindings.get("fieldTypes");
        assertEquals("solr.StrField",  fieldTypes.get("id"));
        assertEquals("solr.TextField", fieldTypes.get("title"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractBindings_withFieldTypesAndSolrConfigBothInBindingsObject_extractsBoth() {
        // Both fieldTypes and solrConfig can be inlined together in bindingsObject
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{" +
            "\\\"solrConfig\\\":{\\\"/select\\\":{\\\"defaults\\\":{\\\"df\\\":\\\"title\\\"}}}," +
            "\\\"fieldTypes\\\":{\\\"status\\\":\\\"solr.StrField\\\"}" +
            "}\"}}]");

        assertTrue(bindings.containsKey("solrConfig"));
        assertTrue(bindings.containsKey("fieldTypes"));
        var fieldTypes = (java.util.Map<String, String>) bindings.get("fieldTypes");
        assertEquals("solr.StrField", fieldTypes.get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractBindings_withSolrConfigXmlFile_parsesXml(@TempDir java.nio.file.Path tempDir) throws Exception {
        var xml = tempDir.resolve("solrconfig.xml");
        java.nio.file.Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults"><str name="df">title</str></lst>
              </requestHandler>
            </config>
            """);
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{}\",\"solrConfigXmlFile\":\"" + xml.toAbsolutePath() + "\"}}]");
        assertTrue(bindings.containsKey("solrConfig"));
        var solrConfig = (java.util.Map<String, Object>) bindings.get("solrConfig");
        assertTrue(solrConfig.containsKey("/select"));
    }

    @Test
    void extractBindings_withSolrSchemaXmlFile_parsesXml(@TempDir java.nio.file.Path tempDir) throws Exception {
        var xml = tempDir.resolve("managed-schema.xml");
        java.nio.file.Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <schema name="test" version="1.6">
              <fieldType name="string"       class="solr.StrField"/>
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="id"    type="string"/>
              <field name="title" type="text_general"/>
            </schema>
            """);

        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{}\",\"solrSchemaXmlFile\":\"" + xml.toAbsolutePath() + "\"}}]");

        assertTrue(bindings.containsKey("fieldTypes"));
        @SuppressWarnings("unchecked")
        var fieldTypes = (java.util.Map<String, String>) bindings.get("fieldTypes");
        assertEquals("solr.StrField",  fieldTypes.get("id"));
        assertEquals("solr.TextField", fieldTypes.get("title"));
    }

    @Test
    void extractBindings_withBothSchemaAndConfigXmlFiles_loadsBoth(@TempDir java.nio.file.Path tempDir) throws Exception {
        var schemaXml = tempDir.resolve("managed-schema.xml");
        java.nio.file.Files.writeString(schemaXml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <schema name="test" version="1.6">
              <fieldType name="string" class="solr.StrField"/>
              <field name="id" type="string"/>
            </schema>
            """);

        var configXml = tempDir.resolve("solrconfig.xml");
        java.nio.file.Files.writeString(configXml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults"><str name="df">title</str></lst>
              </requestHandler>
            </config>
            """);

        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{}\"," +
            "\"solrConfigXmlFile\":\"" + configXml.toAbsolutePath() + "\"," +
            "\"solrSchemaXmlFile\":\"" + schemaXml.toAbsolutePath() + "\"}}]");

        assertTrue(bindings.containsKey("solrConfig"),  "solrConfig should be present");
        assertTrue(bindings.containsKey("fieldTypes"),  "fieldTypes should be present");
    }

    @Test
    void extractBindings_withMissingSolrSchemaXmlFile_noFieldTypesKey(@TempDir java.nio.file.Path tempDir) {
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"," +
            "\"bindingsObject\":\"{}\"," +
            "\"solrSchemaXmlFile\":\"/nonexistent/managed-schema.xml\"}}]");

        // Missing file → SolrSchemaProvider returns empty map → not put into bindings
        assertTrue(!bindings.containsKey("fieldTypes"),
            "fieldTypes should not be present when schema file is missing");
    }

    @Test
    void extractBindings_invalidJson_returnsEmpty() {
        var bindings = ShimMain.extractBindings("not valid json");
        assertTrue(bindings.isEmpty());
    }

    @Test
    void extractBindings_noBindingsKey_returnsEmpty() {
        var bindings = ShimMain.extractBindings(
            "[{\"SolrTransformerProvider\":{\"initializationScript\":\"x\"}}]");
        assertTrue(bindings.isEmpty());
    }

    @Test
    void createTransformer_withWatch_preservesBindings() {
        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = "[{\"SolrTransformerProvider\":{" +
            "\"initializationScript\":\"(function(bindings) { return function(msg) { return msg; }; })\"," +
            "\"bindingsObject\":\"{\\\"solrConfig\\\":{\\\"/select\\\":{\\\"defaults\\\":{\\\"df\\\":\\\"title\\\"}}}}\"}}]";
        var watched = new java.util.LinkedHashMap<java.nio.file.Path, ReloadableTransformer>();
        var result = ShimMain.createTransformer(params, true, watched);
        assertTrue(result instanceof ReloadableTransformer);
        var reloadable = (ReloadableTransformer) result;
        assertNotNull(reloadable.getBindings());
        assertTrue(reloadable.getBindings().containsKey("solrConfig"));
    }

    // --- parseTargets ---

    @Test
    void parseTargets_transformTargetDefaultsToNonPrimary() throws Exception {
        var p = parse("--listenPort", "8080",
            "--target", "solr=http://localhost:8983",
            "--target", "os=http://localhost:9200",
            "--primary", "solr");
        var targets = ShimMain.parseTargets(p, new java.util.LinkedHashMap<>());
        // os should be the transform target (non-primary)
        assertNull(targets.get("solr").requestTransform());
        // no transforms configured, so even the transform target has null
        assertNull(targets.get("os").requestTransform());
    }

    @Test
    void parseTargets_explicitTransformTarget() throws Exception {
        var p = parse("--listenPort", "8080",
            "--target", "solr=http://localhost:8983",
            "--target", "os=http://localhost:9200",
            "--primary", "solr",
            "--transformTarget", "os");
        var targets = ShimMain.parseTargets(p, new java.util.LinkedHashMap<>());
        assertEquals(2, targets.size());
        assertNull(targets.get("solr").requestTransform());
    }

    @Test
    void parseTargets_unknownTransformTarget_throws() {
        var p = parse("--listenPort", "8080",
            "--target", "os=http://localhost:9200",
            "--primary", "os",
            "--transformTarget", "nonexistent");
        assertThrows(Exception.class,
            () -> ShimMain.parseTargets(p, new java.util.LinkedHashMap<>()));
    }

    @Test
    void parseTargets_invalidTargetSpec_throws() {
        var p = parse("--listenPort", "8080",
            "--target", "badformat",
            "--primary", "os");
        assertThrows(Exception.class,
            () -> ShimMain.parseTargets(p, new java.util.LinkedHashMap<>()));
    }

    // --- createTransformer ---

    @Test
    void createTransformer_nullConfig_returnsNull() {
        var params = new ShimMain.RequestTransformationParams();
        // no config set — all fields null
        var result = ShimMain.createTransformer(params, false, new java.util.LinkedHashMap<>());
        assertNull(result);
    }

    @Test
    void createTransformer_withValidConfig_returnsTransformer() {
        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = "[{\"SolrTransformerProvider\":{" +
            "\"initializationScript\":\"(function(bindings) { return function(msg) { return msg; }; })\"," +
            "\"bindingsObject\":\"{}\"}}]";
        var watched = new java.util.LinkedHashMap<java.nio.file.Path, org.opensearch.migrations.transform.shim.ReloadableTransformer>();
        var result = ShimMain.createTransformer(params, false, watched);
        assertNotNull(result);
        assertTrue(watched.isEmpty(), "watch=false should not populate watched map");
    }

    @Test
    void createTransformer_withWatch_wrapsInReloadable() {
        var params = new ShimMain.RequestTransformationParams();
        params.transformerConfig = "[{\"SolrTransformerProvider\":{" +
            "\"initializationScript\":\"(function(bindings) { return function(msg) { return msg; }; })\"," +
            "\"bindingsObject\":\"{}\"}}]";
        var watched = new java.util.LinkedHashMap<java.nio.file.Path, org.opensearch.migrations.transform.shim.ReloadableTransformer>();
        var result = ShimMain.createTransformer(params, true, watched);
        assertNotNull(result);
        assertTrue(result instanceof org.opensearch.migrations.transform.shim.ReloadableTransformer,
            "watch=true should return ReloadableTransformer");
        // no initializationScriptFile in config, so watched map stays empty (inline script)
        assertTrue(watched.isEmpty());
    }

    @Test
    void createTransformer_withWatchAndScriptFile_populatesWatchedMap() throws Exception {
        var scriptFile = java.nio.file.Files.createTempFile("test-transform", ".js");
        java.nio.file.Files.writeString(scriptFile,
            "(function(bindings) { return function(msg) { return msg; }; })");
        try {
            var params = new ShimMain.RequestTransformationParams();
            params.transformerConfig = "[{\"SolrTransformerProvider\":{" +
                "\"initializationScriptFile\":\"" + scriptFile.toAbsolutePath() + "\"," +
                "\"bindingsObject\":\"{}\"}}]";
            var watched = new java.util.LinkedHashMap<java.nio.file.Path, org.opensearch.migrations.transform.shim.ReloadableTransformer>();
            var result = ShimMain.createTransformer(params, true, watched);
            assertNotNull(result);
            assertTrue(result instanceof org.opensearch.migrations.transform.shim.ReloadableTransformer);
            assertEquals(1, watched.size(), "watched map should have the script file path");
        } finally {
            java.nio.file.Files.deleteIfExists(scriptFile);
        }
    }

    // --- parseTargets with actual transforms ---

    @Test
    void parseTargets_withTransformerConfig_appliesTransformToTarget() throws Exception {
        var scriptFile = java.nio.file.Files.createTempFile("test-transform", ".js");
        java.nio.file.Files.writeString(scriptFile,
            "(function(bindings) { return function(msg) { return msg; }; })");
        try {
            var p = parse("--listenPort", "8080",
                "--target", "solr=http://localhost:8983",
                "--target", "os=http://localhost:9200",
                "--primary", "solr",
                "--transformerConfig", "[{\"SolrTransformerProvider\":{" +
                    "\"initializationScriptFile\":\"" + scriptFile.toAbsolutePath() + "\"," +
                    "\"bindingsObject\":\"{}\"}}]",
                "--transformTarget", "os");
            var watched = new java.util.LinkedHashMap<java.nio.file.Path, org.opensearch.migrations.transform.shim.ReloadableTransformer>();
            var targets = ShimMain.parseTargets(p, watched);
            assertNull(targets.get("solr").requestTransform(), "solr should have no transform");
            assertNotNull(targets.get("os").requestTransform(), "os should have request transform");
        } finally {
            java.nio.file.Files.deleteIfExists(scriptFile);
        }
    }

    @Test
    void parseTargets_singleTarget_noTransformTarget() throws Exception {
        var p = parse("--listenPort", "8080",
            "--target", "os=http://localhost:9200",
            "--primary", "os");
        var targets = ShimMain.parseTargets(p, new java.util.LinkedHashMap<>());
        assertEquals(1, targets.size());
        // single target is primary, no non-primary to apply transforms to
        assertNull(targets.get("os").requestTransform());
    }

    // --- Reporting config ---

    @Test
    void reportingConfig_camelCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--reportingConfig",
            "[{\"FileSystemReportingSink\":{\"outputDir\":\"/tmp/reports\"}}]");
        assertNotNull(p.reportingParams.reportingConfig);
    }

    @Test
    void reportingConfig_kebabCase() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200",
            "--primary", "os", "--reporting-config",
            "[{\"FileSystemReportingSink\":{\"outputDir\":\"/tmp/reports\"}}]");
        assertNotNull(p.reportingParams.reportingConfig);
    }

    @Test
    void reportingConfig_defaultsToNull() {
        var p = parse("--listenPort", "8080", "--target", "os=http://localhost:9200", "--primary", "os");
        assertNull(p.reportingParams.reportingConfig);
    }

    @Test
    void parseReportingConfig_validJson() {
        var entry = ShimMain.parseReportingConfig(
            "[{\"FileSystemReportingSink\":{\"outputDir\":\"/tmp/reports\",\"bufferSize\":2048}}]");
        assertEquals("FileSystemReportingSink", entry.getKey());
        assertEquals("/tmp/reports", entry.getValue().get("outputDir"));
        assertEquals(2048, ((Number) entry.getValue().get("bufferSize")).intValue());
    }

    @Test
    void parseReportingConfig_minimalJson() {
        var entry = ShimMain.parseReportingConfig(
            "[{\"FileSystemReportingSink\":{\"outputDir\":\"/tmp/reports\"}}]");
        assertEquals("FileSystemReportingSink", entry.getKey());
        assertEquals("/tmp/reports", entry.getValue().get("outputDir"));
    }

    @Test
    void parseReportingConfig_missingOutputDir_doesNotThrow() {
        // parse only extracts structure — content validation is in createReportingSink
        var entry = ShimMain.parseReportingConfig(
            "[{\"FileSystemReportingSink\":{\"bufferSize\":1024}}]");
        assertEquals("FileSystemReportingSink", entry.getKey());
    }

    @Test
    void parseReportingConfig_invalidJson_throws() {
        assertThrows(Exception.class, () -> ShimMain.parseReportingConfig("not json"));
    }

    @Test
    void parseReportingConfig_emptyArray_throws() {
        assertThrows(Exception.class, () -> ShimMain.parseReportingConfig("[]"));
    }

    @Test
    void parseReportingConfig_nonObjectEntry_throws() {
        assertThrows(Exception.class, () -> ShimMain.parseReportingConfig("[123]"));
    }

    @Test
    void parseReportingConfig_multipleProviderKeys_throws() {
        assertThrows(Exception.class, () -> ShimMain.parseReportingConfig(
            "[{\"A\":{\"outputDir\":\"/tmp\"},\"B\":{\"outputDir\":\"/tmp\"}}]"));
    }

    @Test
    void parseReportingConfig_nonObjectProviderValue_throws() {
        assertThrows(Exception.class, () -> ShimMain.parseReportingConfig(
            "[{\"FileSystemReportingSink\":\"not an object\"}]"));
    }

    @Test
    void createReportingSink_createsWithDefaults(@TempDir java.nio.file.Path tmpDir) throws Exception {
        var config = java.util.Map.<String, Object>of("outputDir", tmpDir.toAbsolutePath().toString());
        var sink = ShimMain.createReportingSink("FileSystemReportingSink", config);
        assertNotNull(sink);
        sink.close();
    }

    @Test
    void createReportingSink_respectsBufferSize(@TempDir java.nio.file.Path tmpDir) throws Exception {
        var config = java.util.Map.<String, Object>of(
            "outputDir", tmpDir.toAbsolutePath().toString(), "bufferSize", 512);
        var sink = ShimMain.createReportingSink("FileSystemReportingSink", config);
        assertNotNull(sink);
        sink.close();
    }

    @Test
    void createReportingSink_unknownProvider_throws() {
        var config = java.util.Map.<String, Object>of("outputDir", "/tmp/reports");
        var ex = assertThrows(Exception.class,
            () -> ShimMain.createReportingSink("S3ReportingSink", config));
        assertTrue(ex.getMessage().contains("Unknown reporting provider"));
    }

    @Test
    void createReportingSink_missingOutputDir_throws() {
        var config = java.util.Map.<String, Object>of("bufferSize", 1024);
        assertThrows(Exception.class,
            () -> ShimMain.createReportingSink("FileSystemReportingSink", config));
    }

    @Test
    void createMetricsReceiver_defaultFlags() {
        var mockSink = org.mockito.Mockito.mock(
            org.opensearch.migrations.transform.shim.reporting.ReportingSink.class);
        var config = java.util.Map.<String, Object>of("outputDir", "/tmp/x");
        var receiver = ShimMain.createMetricsReceiver(config, mockSink);
        assertNotNull(receiver);
    }

    @Test
    void createMetricsReceiver_withBodyFlags() {
        var mockSink = org.mockito.Mockito.mock(
            org.opensearch.migrations.transform.shim.reporting.ReportingSink.class);
        var config = java.util.Map.<String, Object>of(
            "outputDir", "/tmp/x", "includeRequestBody", true, "includeResponseBody", true);
        var receiver = ShimMain.createMetricsReceiver(config, mockSink);
        assertNotNull(receiver);
    }

    @Test
    void buildReporting_nullConfig_returnsDisabled() {
        var result = ShimMain.buildReporting(null);
        assertNull(result.reportingSink());
        assertNull(result.metricsReceiver());
    }

    @Test
    void buildReporting_validConfig_returnsBothComponents(@TempDir java.nio.file.Path tmpDir) throws Exception {
        var result = ShimMain.buildReporting(
            "[{\"FileSystemReportingSink\":{\"outputDir\":\"" + tmpDir.toAbsolutePath() + "\"}}]");
        assertNotNull(result.reportingSink());
        assertNotNull(result.metricsReceiver());
        result.reportingSink().close();
    }

    // --- ServiceLoader discovery ---

    @Test
    void solrTransformerProvider_discoveredByServiceLoader() {
        var loader = java.util.ServiceLoader.load(
            org.opensearch.migrations.transform.IJsonTransformerProvider.class);
        boolean found = false;
        for (var provider : loader) {
            if (provider instanceof org.opensearch.migrations.transform.shim.SolrTransformerProvider) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SolrTransformerProvider should be discoverable via ServiceLoader");
    }
}
