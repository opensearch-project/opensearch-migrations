package org.opensearch.migrations.transform.flags;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FeatureFlagsDeserializer extends StdDeserializer<FeatureFlags> {

    public FeatureFlagsDeserializer() {
        this(null);
    }

    public FeatureFlagsDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public FeatureFlags deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        FeatureFlags featureFlags = new FeatureFlags();
        JsonNode node = p.getCodec().readTree(p);

        for (var entry : node.properties()) {
            final var key = entry.getKey();
            final var value = entry.getValue();
            if (value.isObject()) {
                JsonParser valueParser = value.traverse();
                valueParser.setCodec(p.getCodec());
                featureFlags.put(key, ctx.readValue(valueParser, FeatureFlags.class));
            } else {
                featureFlags.put(key, Map.of(FeatureFlags.ENABLED_KEY, value.booleanValue()));
            }
        }
        // If 'enabled' is not explicitly set, default to true
        if (!featureFlags.containsKey(FeatureFlags.ENABLED_KEY)) {
            featureFlags.put(FeatureFlags.ENABLED_KEY, true);
        } else if (!(featureFlags.get(FeatureFlags.ENABLED_KEY) instanceof Boolean)) {
            throw new IllegalArgumentException("enabled key must map to a boolean value");
        }
        return featureFlags;
    }
}
