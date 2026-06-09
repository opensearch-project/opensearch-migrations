package org.opensearch.migrations.transform.replay;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TransformationLoaderTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> parseAsMap(String contents) throws Exception {
        return mapper.readValue(contents.getBytes(), new TypeReference<>() {
        });
    }

    @Test
    public void testTransformationLoader() throws Exception {
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("testhostname");
        var toOldHostTransformer = new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost");
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        var origDocStr = mapper.writeValueAsString(origDoc);
        Object outputWithNewHostname = toNewHostTransformer.transformJson(origDoc);
        var docWithNewHostnameStr = mapper.writeValueAsString(outputWithNewHostname);
        Object outputWithOldHostname = toOldHostTransformer.transformJson(outputWithNewHostname);
        var docWithOldHostnameStr = mapper.writeValueAsString(outputWithOldHostname);
        Assertions.assertEquals(origDocStr, docWithOldHostnameStr);
        Assertions.assertNotEquals(origDocStr, docWithNewHostnameStr);
    }

    @Test
    public void testThatSimpleNoopTransformerLoads() throws Exception {
        var noopTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "localhost",
            null,
            "NoopTransformerProvider"
        );
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        Object output = noopTransformer.transformJson(origDoc);
        Assertions.assertEquals(mapper.writeValueAsString(origDoc), mapper.writeValueAsString(output));
    }

    @Test
    public void testMisconfiguration() {
        var transformLoader = new TransformationLoader();
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> transformLoader.getTransformerFactoryLoader("localhost", null, "Not right")
        );
    }

    @Test
    public void testThatNoConfigMeansNoThrow() throws Exception {
        var transformer = Assertions.assertDoesNotThrow(
            () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost")
        );
        Assertions.assertNotNull(transformer);
        var origDoc = parseAsMap(SampleContents.loadSampleJsonRequestAsString());
        Assertions.assertNotNull(transformer.transformJson(origDoc));
    }

    @Test
    public void testProviderConfigFilesAreResolvedBeforeProviderCreation() throws Exception {
        var scriptFile = Files.createTempFile("jmespath-transform", ".txt");
        try {
            Files.writeString(scriptFile, "headers.host");
            var fullConfig = mapper.writeValueAsString(List.of(Map.of(
                "JsonJMESPathTransformerProvider",
                Map.of(
                    "providerConfigFiles", Map.of("script", Map.of("path", scriptFile.toString()))))));

            var transformer = new TransformationLoader().getTransformerFactoryLoader(null, null, fullConfig);
            var transformed = transformer.transformJson(parseAsMap(TEST_INPUT_REQUEST));

            Assertions.assertEquals("127.0.0.1", transformed);
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    @Test
    public void testProviderConfigFilesUseProviderDeclaredValueTypes() throws Exception {
        var configDir = Files.createTempDirectory("type-mapping-provider-config");
        var regexMappingsFile = Files.createTempFile("type-mapping-regex", ".json");
        try {
            Files.writeString(configDir.resolve("sourceProperties"), "{\"version\":{\"major\":7,\"minor\":10}}");
            Files.writeString(configDir.resolve("featureFlags"), "{}");
            Files.writeString(regexMappingsFile, "[]");

            var fullConfig = mapper.writeValueAsString(List.of(Map.of(
                "TypeMappingSanitizationTransformerProvider",
                Map.of(
                    "providerConfigDirs", List.of(Map.of("path", configDir.toString())),
                    "providerConfigFiles", Map.of("regexMappings", Map.of("path", regexMappingsFile.toString()))))));

            var transformer = new TransformationLoader().getTransformerFactoryLoader(null, null, fullConfig);
            var transformed = transformer.transformJson(parseAsMap(TEST_INPUT_REQUEST));

            Assertions.assertNotNull(transformed);
        } finally {
            Files.deleteIfExists(configDir.resolve("sourceProperties"));
            Files.deleteIfExists(configDir.resolve("featureFlags"));
            Files.deleteIfExists(configDir);
            Files.deleteIfExists(regexMappingsFile);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testProviderConfigFilesSupportProviderDeclaredMaterializationTypes() throws Exception {
        var jsonValueFile = Files.createTempFile("materialization-json", ".json");
        var textValueFile = Files.createTempFile("materialization-text", ".txt");
        var bytesValueFile = Files.createTempFile("materialization-bytes", ".bin");
        var base64ValueFile = Files.createTempFile("materialization-base64", ".bin");
        var pathValueFile = Files.createTempFile("materialization-path", ".txt");
        try {
            Files.writeString(jsonValueFile, "{\"enabled\":true}");
            Files.writeString(textValueFile, "hello");
            Files.write(bytesValueFile, new byte[]{1, 2, 3});
            Files.write(base64ValueFile, new byte[]{1, 2, 3});
            Files.writeString(pathValueFile, "ignored");

            var fullConfig = mapper.writeValueAsString(List.of(Map.of(
                "MaterializationProbeTransformerProvider",
                Map.of("providerConfigFiles", Map.of(
                    "jsonValue", Map.of("path", jsonValueFile.toString()),
                    "textValue", Map.of("path", textValueFile.toString()),
                    "bytesValue", Map.of("path", bytesValueFile.toString()),
                    "base64Value", Map.of("path", base64ValueFile.toString()),
                    "pathValue", Map.of("path", pathValueFile.toString()))))));

            var transformer = new TransformationLoader().getTransformerFactoryLoader(null, null, fullConfig);
            var transformed = (Map<String, Object>) transformer.transformJson(parseAsMap(TEST_INPUT_REQUEST));

            Assertions.assertEquals(true, ((Map<String, Object>) transformed.get("jsonValue")).get("enabled"));
            Assertions.assertEquals("hello", transformed.get("textValue"));
            Assertions.assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) transformed.get("bytesValue"));
            Assertions.assertEquals("AQID", transformed.get("base64Value"));
            Assertions.assertEquals(pathValueFile.toString(), transformed.get("pathValue"));
        } finally {
            Files.deleteIfExists(jsonValueFile);
            Files.deleteIfExists(textValueFile);
            Files.deleteIfExists(bytesValueFile);
            Files.deleteIfExists(base64ValueFile);
            Files.deleteIfExists(pathValueFile);
        }
    }

    static final String TEST_INPUT_REQUEST = "{\n"
        + "  \"method\": \"PUT\",\n"
        + "  \"URI\": \"/oldStyleIndex\",\n"
        + "  \"headers\": {\n"
        + "    \"host\": \"127.0.0.1\"\n"
        + "  },\n"
        + "  \"payload\": {\n}\n"
        + "}\n";

    @Test
    @SuppressWarnings("unchecked")
    public void testUserAgentAppends() throws Exception {
        var userAgentTransformer = new TransformationLoader().getTransformerFactoryLoader("localhost", "tester", null);

        var origDoc = parseAsMap(TEST_INPUT_REQUEST);
        Object pass1 = userAgentTransformer.transformJson(origDoc);
        Object pass2 = userAgentTransformer.transformJson(pass1);
        var finalUserAgentInHeaders = ((Map<String, Object>) ((Map<String, Object>) pass2).get("headers")).get("user-agent");
        Assertions.assertEquals("tester; tester", finalUserAgentInHeaders);
    }
}
