package org.opensearch.migrations.dashboards.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.dashboards.converter.QueryConverter;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class QueryConverterTest extends SavedObjectsBase {
    

    @Test
    public void testMigrationVersionShouldNotBePresent() throws JsonProcessingException {
        // Given a json data with no 'allowNoIndex' attribute

        final ObjectNode json = this.loadJsonFile("/data/query/input-001-version-7.17.4.json");
        final QueryConverter queryConverter = new QueryConverter();
        
        // When calling convert
        final SavedObject converted = queryConverter.convert(new SavedObject(json));
        
        // Then the migration version should be set to 7.6.0 only
        assertNull(converted.json().get("migrationVersion"));
        
        final ObjectNode original = this.loadJsonFile("/data/query/input-001-version-7.17.4.json");

        assertEquals(original.get("attributes"), converted.attributes());
        assertEquals(original.get("references"), converted.json().get("references"));
    }

    @Test
    public void testShouldPresentOnlyAllowedAttributes() throws JsonProcessingException {
        // Given a json data with no 'allowNoIndex' attribute

        final ObjectNode json = this.loadJsonFile("/data/query/input-001-version-7.17.4.json");
        json.withObject("attributes").put("invalidField", true);
        final QueryConverter queryConverter = new QueryConverter();
        
        // When calling convert
        final SavedObject converted = queryConverter.convert(new SavedObject(json));
        
        // Then the migration version should be set to 7.6.0 only
        assertNull(converted.json().get("migrationVersion"));
        
        final ObjectNode original = this.loadJsonFile("/data/query/input-001-version-7.17.4.json");

        assertEquals(original.get("attributes"), converted.attributes());
        assertEquals(original.get("references"), converted.json().get("references"));
    }

}
