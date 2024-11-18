package org.opensearch.migrations.transform;

import java.util.Collections;
import java.util.Map;

import org.apache.commons.collections4.map.CompositeMap;


public class JsonJinjavaTransformerProvider implements IJsonTransformerProvider {

    public static final String REQUEST_KEY = "request";
    public static final String TEMPLATE_KEY = "template";

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }
        var config = (Map<String, Object>) jsonConfig;
        if (config.containsKey(REQUEST_KEY)) {
            throw new IllegalArgumentException(REQUEST_KEY + " was already present in the incoming configuration.  " +
                getConfigUsageStr());
        }
        if (!config.containsKey(TEMPLATE_KEY)) {
            throw new IllegalArgumentException(TEMPLATE_KEY + " was not present in the incoming configuration.  " +
                getConfigUsageStr());
        }

        var immutableBaseConfig = Collections.unmodifiableMap(config);
        try {
            var templateString = (String) config.get(TEMPLATE_KEY);
            return new JinjavaTransformer(templateString,
                source -> new CompositeMap<>(Map.of(REQUEST_KEY, source), immutableBaseConfig));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object> " +
            "with a '" + TEMPLATE_KEY + "' key that specifies the Jinjava template.  " +
            "The key '" + REQUEST_KEY + "' must not be specified so that it can be used to pass the source document " +
            "into the template.  " +
            "The other top-level keys will be passed directly to the template.";
    }
}
