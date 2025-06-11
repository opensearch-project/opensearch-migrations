package org.opensearch.migrations.bulkload.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class ObjectMapperFactory {

    /**
     * Returns a default ObjectMapper with fail-on-unknown-properties disabled.
     */
    public static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Returns a custom ObjectMapper with fail-on-unknown-properties disabled,
     * and optional Jackson modules applied (e.g., for custom deserializers).
     */
    public static ObjectMapper createWithModules(SimpleModule... modules) {
        ObjectMapper mapper = createDefaultMapper();
        for (SimpleModule module : modules) {
            mapper.registerModule(module);
        }
        return mapper;
    }

    private ObjectMapperFactory() {
        // Prevent instantiation
    }
}
