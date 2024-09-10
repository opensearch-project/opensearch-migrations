package org.opensearch.migrations.dashboards.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.dashboards.converter.SearchConverter;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SearchConverterTest extends SavedObjectsBase {

    @Test
    public void testSearchConverterWithNewerThen7_9_3() throws JsonProcessingException {
        ObjectNode json = loadJsonFile("/data/search/input-001-version-8.8.0.json");
        final String originalStr = objectMapper.writeValueAsString(json);
        SearchConverter search = new SearchConverter();
        
        SavedObject converted = search.convert(new SavedObject(json));

        // Then the migration version should be set to 7.9.3 only
        assertEquals("7.9.3", converted.json().at("/migrationVersion/search").textValue());

        // and no changes should be made to the json data
        converted.json().remove("migrationVersion");
        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }

    @Test
    public void testSearchConverterWith7_9_3() throws JsonProcessingException {
        ObjectNode json = loadJsonFile("/data/search/input-001-version-7.9.3.json");
        final String originalStr = objectMapper.writeValueAsString(json);
        SearchConverter search = new SearchConverter();
        
        SavedObject converted = search.convert(new SavedObject(json));

        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }

    @Test
    public void testSearchConverterWith7_5_0() throws JsonProcessingException {
        ObjectNode json = loadJsonFile("/data/search/input-001-version-7.5.0.json");
        final String originalStr = objectMapper.writeValueAsString(json);
        SearchConverter search = new SearchConverter();
        
        SavedObject converted = search.convert(new SavedObject(json));

        assertEquals(originalStr, objectMapper.writeValueAsString(converted.json()));
    }
    
}
