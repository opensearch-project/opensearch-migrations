package org.opensearch.migrations.dashboards.converters;

import org.opensearch.migrations.dashboards.converter.IndexPatternConverter;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


public class IndexPatternConverterTest extends SavedObjectsBase {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testConvertInnerWithoutAllowNoIndex() throws JsonProcessingException {
        // Given a json data with no 'allowNoIndex' attribute
        final ObjectNode json = this.loadJsonFile("/data/index-pattern/input-001-version-8.8.0.json");
        final IndexPatternConverter indexPattern = new IndexPatternConverter();
        final String originalStr = objectMapper.writeValueAsString(json);

        // When calling convert
        final SavedObject converted = indexPattern.convert(new SavedObject(json));

        // Then the migration version should be set to 7.6.0 only
        assertEquals("7.6.0", converted.json().at("/migrationVersion/index-pattern").asText());

        // and no changes should be made to the json data
        converted.json().remove("migrationVersion");
        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }

    @Test
    public void testConvertInnerWithAllowNoIndex() throws JsonMappingException, JsonProcessingException {
        // Given a json data with no 'allowNoIndex' attribute
        final ObjectNode json = this.loadJsonFile("/data/index-pattern/input-002-version-8.8.0-with-allow-no-index.json");
        final IndexPatternConverter indexPattern = new IndexPatternConverter();

        // When calling convert
        final SavedObject converted = indexPattern.convert(new SavedObject(json));

        // Then the migration version should be set to 7.6.0 only
        assertEquals("7.6.0", converted.json().at("/migrationVersion/index-pattern").asText());
        assertNull(converted.attributes().get("allowNoIndex"));
    }

    @Test
    public void testConvertInnerFromVersion760() throws JsonMappingException, JsonProcessingException {
        // Case where version is = 7.6.0
        final ObjectNode json = this.loadJsonFile("/data/index-pattern/input-003-version-7.6.0.json");
        final IndexPatternConverter indexPattern = new IndexPatternConverter();
        final String originalStr = objectMapper.writeValueAsString(json);

        // When calling convert
        final SavedObject converted = indexPattern.convert(new SavedObject(json));

        assertEquals("7.6.0", converted.json().at("/migrationVersion/index-pattern").asText());

        // and no changes should be made to the json data
        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }

    @Test
    public void testConvertInnerFromVersion750() throws JsonMappingException, JsonProcessingException {
        // Case where version is = 7.5.0
        final ObjectNode json = this.loadJsonFile("/data/index-pattern/input-004-version-7.5.0.json");
        final IndexPatternConverter indexPattern = new IndexPatternConverter();
        final String originalStr = objectMapper.writeValueAsString(json);

        // When calling convert
        final SavedObject converted = indexPattern.convert(new SavedObject(json));

        // and no changes should be made to the json data
        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }
}
