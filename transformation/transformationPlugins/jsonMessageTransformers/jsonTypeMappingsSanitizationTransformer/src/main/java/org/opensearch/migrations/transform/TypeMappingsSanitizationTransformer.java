package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.opensearch.migrations.transform.jinjava.JinjavaConfig;
import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.google.common.io.Resources;

public class TypeMappingsSanitizationTransformer extends JinjavaTransformer {

    public static final String ENTRYPOINT_JINJA_TEMPLATE = "jinjava/typeMappings/transformByTypeOfSourceInput.j2";

    public TypeMappingsSanitizationTransformer(
        Map<String, Map<String, String>> indexMappings,
        List<List<String>> regexIndexMappings)
        throws IOException {
        this(indexMappings, regexIndexMappings, null, null, null);
    }

    public TypeMappingsSanitizationTransformer(
        Map<String, Map<String, String>> indexMappings,
        List<List<String>> regexIndexMappings,
        SourceProperties sourceProperties,
        Map<String, Object> featureFlags,
        JinjavaConfig jinjavaSettings)
        throws IOException
    {
        super(
            makeTemplate(),
            makeSourceWrapperFunction(sourceProperties, featureFlags, indexMappings, regexIndexMappings),
            Optional.ofNullable(jinjavaSettings).orElse(new JinjavaConfig()));
    }

    private static Function<Object, Map<String, Object>>
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
            .orElseGet(() -> (indexMappingsIncoming == null ? List.of(List.of("(.*)", "(.*)", "\\1_\\2")) : List.of()));

        return incomingJson -> Map.of("source_document", incomingJson,
            "index_mappings", indexMappings,
            "regex_index_mappings", regexIndexMappings,
            "featureFlags", featureFlags,
            "source_properties", sourceProperties == null ? Map.of() : sourceProperties);
    }

    private static String makeTemplate() throws IOException {
        return Resources.toString(Resources.getResource(ENTRYPOINT_JINJA_TEMPLATE), StandardCharsets.UTF_8);
    }
}
