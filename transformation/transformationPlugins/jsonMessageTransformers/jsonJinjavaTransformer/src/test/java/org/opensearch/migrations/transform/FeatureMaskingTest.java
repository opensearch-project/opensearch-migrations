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
            "{%- import \"common/featureEnabled.j2\" as fscope -%}" +
            " { " +
            "{%- set ns = namespace(debug_info=['list: ']) -%}" +
            "{%- set ns.debug_info = ['list: '] -%}" +
            "\"enabledFlags\": \"" +
            "{{- fscope.is_enabled(features,'testFlag') -}}," +
            "{{- fscope.is_enabled(features,'testFlag.t1') -}}," +
            "{{- fscope.is_enabled(features,'testFlag.t2') -}}," +
            "{{- fscope.is_enabled(features,'nextTestFlag.n1') -}}" +
            "\"" +
            "}";
        var transformed = new JinjavaTransformer(template,
            src -> flags == null ? Map.of() : Map.of("features", flags));
        return transformed.transformJson(Map.of());
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
