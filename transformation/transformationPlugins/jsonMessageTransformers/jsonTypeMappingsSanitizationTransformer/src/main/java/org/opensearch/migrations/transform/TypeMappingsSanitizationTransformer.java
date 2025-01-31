package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.transform.jsProxyObjects.MapProxyObject;
import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.google.common.io.Resources;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
        throws IOException {
        super(getScripts(),
            makeContext(sourceProperties, featureFlags, indexMappings, regexIndexMappings));
    }

    private static Object
    makeContext(SourceProperties sourceProperties,
                Map<String, Object> featureFlagsIncoming,
                Map<String, Map<String, String>> indexMappingsIncoming,
                List<List<String>> regexIndexMappingsIncoming) {
        var featureFlags = featureFlagsIncoming != null ? featureFlagsIncoming : Map.of();
        var indexMappings = indexMappingsIncoming != null ? indexMappingsIncoming : Map.of();
        // Regex index mappings apply if an index is not found in the index mappings
        // The default is to map each type to its own index, mapping _doc type to the same input index
        var regexIndexMappings = Optional.ofNullable(regexIndexMappingsIncoming)
            .orElse(
                List.of(
                    List.of("(.+)", "_doc", "$1"),
                    List.of("(.+)", "(.+)", "$1_$2")
                )
            );

        return new MapProxyObject(Map.of(
            "index_mappings", indexMappings,
            "regex_index_mappings", regexIndexMappings,
            "featureFlags", featureFlags,
            "source_properties", (sourceProperties == null) ?
                Map.of() :
                Map.of("version",
                    Map.of("major", sourceProperties.getVersion().getMajor(),
                        "minor", sourceProperties.getVersion().getMinor())
                )));
    }

    private static String getScripts() throws IOException {
        return Resources.toString(Resources.getResource(INIT_SCRIPT_RESOURCE_NAME), StandardCharsets.UTF_8);
    }
}
