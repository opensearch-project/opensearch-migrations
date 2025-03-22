package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.google.common.io.Resources;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TypeMappingsSanitizationTransformer extends JavascriptTransformer {

    public static final String INIT_SCRIPT_RESOURCE_NAME = "js/typeMappingsSanitizer.js";

    public TypeMappingsSanitizationTransformer(
        Map<String, Map<String, String>> indexMappings,
        List<Map<String, String>> regexMappings,
        SourceProperties sourceProperties,
        Map<String, Object> featureFlags)
        throws IOException {
        super(getScripts(),
            makeContext(sourceProperties, featureFlags, indexMappings, regexMappings));
    }

    private static Object
    makeContext(SourceProperties sourceProperties,
                Map<String, Object> featureFlagsIncoming,
                Map<String, Map<String, String>> staticMappingsIncoming,
                List<Map<String, String>> regexMappingsIncoming) {
        var featureFlags = featureFlagsIncoming != null ? featureFlagsIncoming : Map.of();
        var indexMappings = staticMappingsIncoming != null ? staticMappingsIncoming : Map.of();
        // Regex  mappings apply if an index is not found in the index mappings
        // The default is to map each type to its own index, mapping _doc type to the same input index
        var regexMappings = Optional.ofNullable(regexMappingsIncoming)
            .orElse(
                    List.of(
                        Map.of(
                                "sourceIndexPattern","(.+)",
                                "sourceTypePattern", "_doc",
                                "targetIndexPattern", "$1"
                        ),
                        Map.of(
                                "sourceIndexPattern","(.+)",
                                "sourceTypePattern", "(.+)",
                                "targetIndexPattern", "$1_$2"
                        )
                    )
            );

        return Map.of(
            "index_mappings", indexMappings,
            "regex_mappings", regexMappings,
            "featureFlags", featureFlags,
            "source_properties", (sourceProperties == null) ?
                Map.of() :
                Map.of("version",
                    Map.of("major", sourceProperties.getVersion().getMajor(),
                        "minor", sourceProperties.getVersion().getMinor())
                ));
    }

    public static String getScripts() throws IOException {
        return Resources.toString(Resources.getResource(INIT_SCRIPT_RESOURCE_NAME), StandardCharsets.UTF_8);
    }
}
