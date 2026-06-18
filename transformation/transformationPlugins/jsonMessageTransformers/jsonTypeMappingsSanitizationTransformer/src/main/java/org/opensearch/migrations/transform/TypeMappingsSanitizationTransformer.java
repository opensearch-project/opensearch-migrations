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
        // Regex mappings apply if an index is not found in the static index mappings.
        // The default unions every type of an index into the index's own name
        // (index/<anyType> -> index), so a multi-type source index is merged into a
        // single target index. This matches the legacy IndexMappingTypeRemoval UNION
        // behavior, which this transformer now supersedes. Callers that need the older
        // type-splitting behavior (index/<type> -> index_<type>) must pass regexMappings
        // explicitly.
        var regexMappings = Optional.ofNullable(regexMappingsIncoming)
            .orElse(
                    List.of(
                        Map.of(
                                "sourceIndexPattern","(.+)",
                                "sourceTypePattern", "(.+)",
                                "targetIndexPattern", "$1"
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
