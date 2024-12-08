package org.opensearch.migrations.testutils;

import java.util.SortedMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.SneakyThrows;

public class JsonNormalizer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, true);

    @SneakyThrows
    public static String fromString(String input) {
        return OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readValue(input, SortedMap.class));
    }

    @SneakyThrows
    public static String fromObject(Object obj) {
        return fromString(OBJECT_MAPPER.writeValueAsString(obj));
    }
}
