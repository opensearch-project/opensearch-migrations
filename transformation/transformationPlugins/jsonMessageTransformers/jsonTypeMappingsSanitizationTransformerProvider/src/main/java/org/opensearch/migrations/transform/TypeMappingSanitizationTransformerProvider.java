package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;

import lombok.SneakyThrows;

public class TypeMappingSanitizationTransformerProvider implements IJsonTransformerProvider {

    public static final String FEATURE_FLAGS = "featureFlags";
    public static final String STATIC_MAPPINGS = "staticMappings";
    public static final String REGEX_MAPPINGS = "regexMappings";

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        try {
            if (jsonConfig == null) {
                return new TypeMappingsSanitizationTransformer(TypeMappingsSanitizationTransformer.REPLAYER_VARIANT,
                    null, null, null);
            } else if (!(jsonConfig instanceof Map)) {
                throw new IllegalArgumentException(getConfigUsageStr());
            }

            var config = (Map<String, Object>) jsonConfig;
            return new TypeMappingsSanitizationTransformer(TypeMappingsSanitizationTransformer.REPLAYER_VARIANT,
                (Map<String, Object>) config.get(FEATURE_FLAGS),
                (Map<String, Map<String, String>>) config.get(STATIC_MAPPINGS),
                (List<List<String>>) config.get(REGEX_MAPPINGS));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName() + " " +
            "expects the incoming configuration to be a Map<String, Object>, " +
            "with values '" + STATIC_MAPPINGS + "', '" + REGEX_MAPPINGS + "', and '" + FEATURE_FLAGS + "'.  " +
            "The value of " + STATIC_MAPPINGS + " should be a two-level map where the top-level key is the name " +
            "of a source index and that key's dictionary maps each sub-type to a specific target index.  " +
            REGEX_MAPPINGS + " (List<[List<String>>]) matches index names and sub-types to a target pattern.  " +
            "The patterns are matched in ascending order, finding the first match.  " +
            "The items within each top-level " + REGEX_MAPPINGS + " element are [indexRegex, typeRegex, targetPattern]." +
            "  The targetPattern can contain backreferences ('\\1'...) to refer to captured groups from the regex.  " +
            "Finally, the " + FEATURE_FLAGS + " is an arbitrarily deep map with boolean leaves.  " +
            "The " + FEATURE_FLAGS + " map is optional.  " +
            "When present, it can disable some types of transformations, " +
            "such as when they may not be applicable for a given migration.";
    }
}
