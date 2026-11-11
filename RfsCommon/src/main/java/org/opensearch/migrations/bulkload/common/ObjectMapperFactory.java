package org.opensearch.migrations.bulkload.common;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class ObjectMapperFactory {
    private static final int MAX_STRING_LENGTH = 100 * 1024 * 1024; // ~100 MB
    private static final int MAX_NAME_LENGTH = 100 * 1024 * 1024; // ~100 MB

    /** The read limits for anything that parses document content, in whatever format it is stored. */
    public static final StreamReadConstraints STREAM_READ_CONSTRAINTS = StreamReadConstraints.builder()
        .maxStringLength(MAX_STRING_LENGTH)
        .maxNameLength(MAX_NAME_LENGTH)
        .build();

    /**
     * Returns a default ObjectMapper with fail-on-unknown-properties disabled.
     */
    public static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = JsonMapper.builder().build();
        mapper.getFactory().setStreamReadConstraints(STREAM_READ_CONSTRAINTS);
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
