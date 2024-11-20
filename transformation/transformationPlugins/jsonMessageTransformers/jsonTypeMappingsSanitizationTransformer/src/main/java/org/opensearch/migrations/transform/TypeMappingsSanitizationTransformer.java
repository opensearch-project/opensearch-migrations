package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.common.io.Resources;

public class TypeMappingsSanitizationTransformer extends JinjavaTransformer {

    public TypeMappingsSanitizationTransformer(Map<String, Map<String, String>> indexMappings) throws IOException {
        this("jinjava/typeMappings/replayer.j2", null, indexMappings);
    }

    public TypeMappingsSanitizationTransformer(String variantName,
                                               Map<String, Object> featureFlags,
                                               Map<String, Map<String, String>> indexMappings) throws IOException {
        super(
            makeTemplate(variantName),
            incomingJson -> Map.of("request", incomingJson,
                    "index_mappings", indexMappings,
                    "featureFlags", featureFlags == null ? Map.of() : featureFlags));
    }

    private static String makeTemplate(String variantName) throws IOException {
        return Resources.toString(Resources.getResource(variantName), StandardCharsets.UTF_8);
    }
}
