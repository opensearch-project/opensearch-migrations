package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.Map;

import org.opensearch.migrations.transform.flags.FeatureFlags;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
public class FeatureMaskingTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public Map<String, Object> transformForMask(Map<String, Object> incomingFlags) throws IOException {
        var incomingFlagStr = objectMapper.writeValueAsString(incomingFlags);
        log.atInfo().setMessage("incoming map as string: {}").addArgument(incomingFlagStr).log();
        var flags = FeatureFlags.parseJson(incomingFlagStr);
        log.atInfo().setMessage("parsed flags: {}").addArgument(flags == null ? "[NULL]" : flags.writeJson()).log();
        final var template = "" +
            "{%- include \"common/featureEnabled.j2\" -%}" +
            " { " +
            "{%- set ns = namespace(debug_info=['list: ']) -%}" +
            "{%- set ns.debug_info = ['list: '] -%}" +
            "\"enabledFlags\": \"" +
            "{{- is_enabled(features,'testFlag') -}}," +
            "{{- is_enabled(features,'testFlag.t1') -}}," +
            "{{- is_enabled(features,'testFlag.t2') -}}," +
            "{{- is_enabled(features,'nextTestFlag.n1') -}}" +
            "\"" +
            "}";
        var sanitization = new JinjavaTransformer(template,
            src -> flags == null ? Map.of() : Map.of("features", flags));
        return sanitization.transformJson(Map.of());
    }

    @Test
    public void testFalseFlag() throws Exception {
        Assertions.assertEquals(
            "false,false,false,false",
            transformForMask(Map.of("testFlag", false)).get("enabledFlags"));
    }

    @Test
    public void testTrueFlag() throws Exception {
        Assertions.assertEquals(
            "true,false,false,false",
            transformForMask(Map.of("testFlag", true)).get("enabledFlags"));
    }

    @Test
    public void testLongerFalseFlag() throws Exception {
        Assertions.assertEquals(
            "false,false,false,false",
            transformForMask(Map.of("testFlag", false)).get("enabledFlags"));
    }

    @Test
    public void testLongerTrueFlag() throws Exception {
        Assertions.assertEquals(
            "true,false,false,false",
            transformForMask(Map.of(
                "testFlag", Map.of("notPresent", false))
            ).get("enabledFlags"));
    }

    @Test
    public void testImplicitTrueFlag() throws Exception {
        Assertions.assertEquals(
            "true,true,false,false",
            transformForMask(Map.of(
                "testFlag", Map.of("t1", true))
            ).get("enabledFlags"));
    }

    @Test
    public void testNullFeatures() throws Exception {
        Assertions.assertEquals(
            "true,true,true,true", transformForMask(null).get("enabledFlags"));
    }
}
