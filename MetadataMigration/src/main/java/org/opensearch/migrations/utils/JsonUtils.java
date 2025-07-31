package org.opensearch.migrations.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for JSON operations.
 * Provides a centralized way to handle JSON serialization across the application.
 */
public class JsonUtils {
    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }
    
    /**
     * Converts an object to JSON string.
     * 
     * @param object The object to convert to JSON
     * @param errorContext Context information for error logging
     * @return JSON string representation of the object
     */
    public static String toJson(Object object, String errorContext) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            log.error("Error converting {} to JSON", errorContext, e);
            return "{ \"error\": \"Failed to convert " + errorContext + " to JSON\" }";
        }
    }
    
    /**
     * Gets the shared ObjectMapper instance.
     * 
     * @return The singleton ObjectMapper instance
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
