package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.google.common.io.Resources;

public class TypeMappingsSanitizationTransformer extends JavascriptTransformer {

    public static final String INIT_SCRIPT_RESOURCE_NAME = "js/typeMappingsSanitizer.js";

    public TypeMappingsSanitizationTransformer(
        Map<String, Map<String, String>> indexMappings,
        List<List<String>> regexIndexMappings)
        throws IOException {
        this(indexMappings, regexIndexMappings, null, null);
    }

    public TypeMappingsSanitizationTransformer(
        Map<String, Map<String, String>> indexMappings,
        List<List<String>> regexIndexMappings,
        SourceProperties sourceProperties,
        Map<String, Object> featureFlags)
        throws IOException
    {
        super(getScripts(), "detectAndTransform(source_document, context)",
            makeSourceWrapperFunction(sourceProperties, featureFlags, indexMappings, regexIndexMappings));
    }

    private static UnaryOperator<Map<String, Object>>
    makeSourceWrapperFunction(SourceProperties sourceProperties,
                              Map<String, Object> featureFlagsIncoming,
                              Map<String, Map<String, String>> indexMappingsIncoming,
                              List<List<String>> regexIndexMappingsIncoming)
    {
        var featureFlags = featureFlagsIncoming != null ? featureFlagsIncoming : Map.of();
        var indexMappings = indexMappingsIncoming != null ? indexMappingsIncoming : Map.of();
        // By NOT including a backreference, we're a bit more efficient, but it also lets us be agnostic to what
        // types of patterns are being used.
        // This regex says, match the type part and reduce it to nothing, leave the index part untouched.
        var regexIndexMappings = Optional.ofNullable(regexIndexMappingsIncoming)
            .orElseGet(() -> (indexMappingsIncoming == null ? List.of(List.of("(.*)", "(.*)", "$1_$2")) : List.of()));

        return incomingJson -> Map.of(
            "source_document", incomingJson,
            "context", Map.of(
                "index_mappings", indexMappings,
                "regex_index_mappings", regexIndexMappings,
                "featureFlags", featureFlags,
                "source_properties", sourceProperties == null ? Map.of() : sourceProperties));
    }

    private static String getScripts() throws IOException {
        return Resources.toString(Resources.getResource(INIT_SCRIPT_RESOURCE_NAME), StandardCharsets.UTF_8);
    }
}
