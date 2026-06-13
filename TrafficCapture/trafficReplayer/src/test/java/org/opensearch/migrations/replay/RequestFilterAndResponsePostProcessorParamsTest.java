package org.opensearch.migrations.replay;

import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.transform.TransformerConfigUtils;

import com.beust.jcommander.JCommander;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestFilterAndResponsePostProcessorParamsTest {

    private TrafficReplayer.RequestFilterParams parseFilterParams(String... args) {
        var params = new TrafficReplayer.RequestFilterParams();
        JCommander.newBuilder().addObject(params).build().parse(args);
        return params;
    }

    private TrafficReplayer.ResponsePostProcessorParams parseResponseParams(String... args) {
        var params = new TrafficReplayer.ResponsePostProcessorParams();
        JCommander.newBuilder().addObject(params).build().parse(args);
        return params;
    }

    @Test
    void requestFilterParams_prefixIsCorrect() {
        var params = new TrafficReplayer.RequestFilterParams();
        assertEquals("request-filter-", params.getTransformerConfigParameterArgPrefix());
    }

    @Test
    void responsePostProcessorParams_prefixIsCorrect() {
        var params = new TrafficReplayer.ResponsePostProcessorParams();
        assertEquals("response-post-processor-", params.getTransformerConfigParameterArgPrefix());
    }

    @Test
    void requestFilterParams_noConfig_returnsNull() {
        var params = new TrafficReplayer.RequestFilterParams();
        assertNull(TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void responsePostProcessorParams_noConfig_returnsNull() {
        var params = new TrafficReplayer.ResponsePostProcessorParams();
        assertNull(TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void requestFilterParams_inlineConfig_returnsConfig() {
        var params = parseFilterParams("--request-filter-config", "{\"test\":\"value\"}");
        assertEquals("{\"test\":\"value\"}", TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void responsePostProcessorParams_inlineConfig_returnsConfig() {
        var params = parseResponseParams("--response-post-processor-config", "[{\"test\":\"value\"}]");
        assertEquals("[{\"test\":\"value\"}]", TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void requestFilterParams_fileConfig_readsFile(@TempDir Path tempDir) throws Exception {
        var configFile = tempDir.resolve("filter.json");
        Files.writeString(configFile, "{\"JsonJMESPathPredicateProvider\":{\"script\":\"true\"}}");
        var params = parseFilterParams("--request-filter-config-file", configFile.toString());
        assertEquals(
            "{\"JsonJMESPathPredicateProvider\":{\"script\":\"true\"}}",
            TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void responsePostProcessorParams_fileConfig_readsFile(@TempDir Path tempDir) throws Exception {
        var configFile = tempDir.resolve("response.json");
        Files.writeString(configFile, "[{\"NoopTransformerProvider\":{}}]");
        var params = parseResponseParams("--response-post-processor-config-file", configFile.toString());
        assertEquals("[{\"NoopTransformerProvider\":{}}]", TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void requestFilterParams_encodedConfig_decodesBase64() {
        // base64 of {"test":"encoded"}
        var params = parseFilterParams("--request-filter-config-encoded", "eyJ0ZXN0IjoiZW5jb2RlZCJ9");
        assertEquals("{\"test\":\"encoded\"}", TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void requestFilterParams_multipleConfigs_throws() {
        var params = parseFilterParams(
            "--request-filter-config", "{\"inline\":true}",
            "--request-filter-config-file", "/some/file.json");
        assertThrows(
            TransformerConfigUtils.TooManyTransformationConfigSourcesException.class,
            () -> TransformerConfigUtils.getTransformerConfig(params));
    }

    @Test
    void buildTransformerSupplier_withNullFilterConfig_returnsBaseTransformer() {
        var supplier = TrafficReplayer.buildTransformerSupplier(
            new org.opensearch.migrations.transform.TransformationLoader(),
            "localhost", null, null, null);
        var transformer = supplier.get();
        assertEquals("JsonCompositeTransformer", transformer.getClass().getSimpleName());
    }

    @Test
    void buildTransformerSupplier_withFilterConfig_returnsFilteringWrapper() {
        var filterConfig = "{\"JsonJMESPathPredicateProvider\":{\"script\":\"contains(URI, '/select')\"}}";
        var supplier = TrafficReplayer.buildTransformerSupplier(
            new org.opensearch.migrations.transform.TransformationLoader(),
            "localhost", null, null, filterConfig);
        var transformer = supplier.get();
        assertEquals("FilteringTransformerWrapper", transformer.getClass().getSimpleName());
    }
}
