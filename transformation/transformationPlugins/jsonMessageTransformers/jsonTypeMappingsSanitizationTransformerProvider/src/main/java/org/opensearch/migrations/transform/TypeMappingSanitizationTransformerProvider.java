package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.transform.typemappings.SourceProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TypeMappingSanitizationTransformerProvider extends JsonJSTransformerProvider {

    public static final String FEATURE_FLAGS = "featureFlags";
    public static final String STATIC_MAPPINGS = "staticMappings";
    public static final String REGEX_MAPPINGS = "regexMappings";
    public static final String SOURCE_PROPERTIES_KEY = "sourceProperties";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SneakyThrows
    @Override
    @SuppressWarnings("unchecked")
    public IJsonTransformer createTransformer(Object jsonConfig) {
        try {
            log.debug("Creating transformer with config: {}", jsonConfig);
            var config = validateAndExtractConfig(jsonConfig, new String[]{SOURCE_PROPERTIES_KEY});
            log.debug("Validated config: {}", config);

            return new TypeMappingsSanitizationTransformer(
                (Map<String, Map<String, String>>) config.get(STATIC_MAPPINGS),
                (List<Map<String, String>>) config.get(REGEX_MAPPINGS),
                Optional.ofNullable(config.get(SOURCE_PROPERTIES_KEY)).map(m ->
                    MAPPER.convertValue(m, SourceProperties.class)).orElse(null),
                (Map<String, Object>) config.get(FEATURE_FLAGS));
        } catch (ClassCastException e) {
            log.error("Configuration error: {}", e.getMessage(), e);
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    @Override
    protected String getConfigUsageStr() {
        return this.getClass().getName() + " " +
            "expects the incoming configuration to be a Map<String, Object>, " +
            "with values (some optional) '" +
            String.join("', '", STATIC_MAPPINGS,REGEX_MAPPINGS,FEATURE_FLAGS,SOURCE_PROPERTIES_KEY) + "'.  " +
            "The value of " + STATIC_MAPPINGS + " should be a two-level map where the top-level key is the name " +
            "of a source index and that key's dictionary maps each sub-type to a specific target index.  " +
            REGEX_MAPPINGS + " (List<[Map<String, String>]) matches index names and sub-types to a target pattern.  " +
            FEATURE_FLAGS + " is a map of feature flags that may alter the behavior of the transformation." +
            SOURCE_PROPERTIES_KEY + " (required) is a nested map of the source cluster version e.g. " +
            "{\"version\":{\"major\":7,\"minor\":10}}.";
    }
}
