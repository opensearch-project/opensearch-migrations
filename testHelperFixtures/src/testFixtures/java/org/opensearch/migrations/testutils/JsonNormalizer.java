package org.opensearch.migrations.testutils;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.SneakyThrows;

public class JsonNormalizer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, true);

    @SneakyThrows
    public static String fromString(String input) {
        Object parsedObject = OBJECT_MAPPER.readValue(input, Object.class);
        return OBJECT_MAPPER.writeValueAsString(convertMapsToSortedMaps(parsedObject));
    }

    @SneakyThrows
    public static String fromObject(Object obj) {
        return fromString(OBJECT_MAPPER.writeValueAsString(obj));
    }
    private static Object convertMapsToSortedMaps(Object obj) {
        if (obj instanceof Map) {
            SortedMap<String, Object> sortedMap = new TreeMap<>();
            ((Map<?, ?>) obj).forEach((key, value) -> sortedMap.put(key.toString(), convertMapsToSortedMaps(value)));
            return sortedMap;
        }
        return obj;
    }
}
