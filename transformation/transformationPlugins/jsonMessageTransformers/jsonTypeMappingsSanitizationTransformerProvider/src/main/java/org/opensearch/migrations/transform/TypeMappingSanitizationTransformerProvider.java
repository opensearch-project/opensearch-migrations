package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.transform.jinjava.JinjavaConfig;
import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class TypeMappingSanitizationTransformerProvider implements IJsonTransformerProvider {

    public static final String FEATURE_FLAGS = "featureFlags";
    public static final String STATIC_MAPPINGS = "staticMappings";
    public static final String REGEX_MAPPINGS = "regexMappings";

    public static final String JINJAVA_CONFIG_KEY = "jinjavaConfig";
    public static final String SOURCE_PROPERTIES_KEY = "sourceProperties";

    public final static ObjectMapper mapper = new ObjectMapper();

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        try {
            if ((jsonConfig == null) ||
                (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
                return new TypeMappingsSanitizationTransformer(null, null, null, null, null);
            } else if (!(jsonConfig instanceof Map)) {
                throw new IllegalArgumentException(getConfigUsageStr());
            }

            var config = (Map<String, Object>) jsonConfig;
            return new TypeMappingsSanitizationTransformer(
                (Map<String, Map<String, String>>) config.get(STATIC_MAPPINGS),
                (List<List<String>>) config.get(REGEX_MAPPINGS),
                Optional.ofNullable(config.get(SOURCE_PROPERTIES_KEY)).map(m ->
                    mapper.convertValue(m, SourceProperties.class)).orElse(null),
                (Map<String, Object>) config.get(FEATURE_FLAGS),
                Optional.ofNullable(config.get(JINJAVA_CONFIG_KEY)).map(m ->
                    mapper.convertValue(m, JinjavaConfig.class)).orElse(null));
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
