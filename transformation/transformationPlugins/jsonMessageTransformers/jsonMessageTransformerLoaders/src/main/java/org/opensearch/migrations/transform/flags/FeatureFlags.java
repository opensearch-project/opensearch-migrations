package org.opensearch.migrations.transform.flags;

import java.io.IOException;
import java.util.HashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonDeserialize(using = FeatureFlagsDeserializer.class)
public class FeatureFlags extends HashMap<String, Object> {

    public static final String ENABLED_KEY = "enabled";

    // Static ObjectMappers for JSON and YAML
    private static final ObjectMapper jsonMapper = new ObjectMapper();


    // Parsing methods
    public static FeatureFlags parseJson(String contents) throws IOException {
        return jsonMapper.readValue(contents, FeatureFlags.class);
    }

    public String writeJson() throws IOException {
        return jsonMapper.writeValueAsString(this);
    }

    @Override
    public String toString() {
        return "FeatureFlags{" +
            "enabled=" + get(ENABLED_KEY) +
            ", features=" + super.toString() +
            '}';
    }
}
