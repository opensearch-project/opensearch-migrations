package org.opensearch.migrations.transform;

import java.util.Map;

public class TypeMappingsSanitizationTransformer extends JinjavaTransformer {

    public TypeMappingsSanitizationTransformer(Map<String, Map<String, String>> indexMappings) {
        this("typeMappings/replayerreplayer", indexMappings);
    }

    public TypeMappingsSanitizationTransformer(String variantName, Map<String, Map<String, String>> indexMappings) {
        super(
            makeTemplate(variantName),
            Map.of("index_mappings", indexMappings),
            incomingJson -> Map.of("request", incomingJson));
    }

    private static String makeTemplate(String variantName) {
        return "{% include \"" + variantName+ "\" %}";
    }
}
