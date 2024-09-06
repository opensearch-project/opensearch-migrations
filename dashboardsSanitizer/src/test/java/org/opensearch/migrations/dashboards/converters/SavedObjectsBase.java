package org.opensearch.migrations.dashboards.converters;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.opensearch.migrations.dashboards.Sanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public abstract class SavedObjectsBase {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        Sanitizer.getInstance().clearQueue();
    }

    public ObjectNode loadJsonFile(String filename) {

        try (InputStream inputStream = getClass().getResourceAsStream(filename)) {
            return (ObjectNode)objectMapper.readTree(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read json file", e);
        }
    }
}
