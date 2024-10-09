package org.opensearch.migrations.dashboards.converters;

import org.opensearch.migrations.dashboards.converter.UrlConverter;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UrlConverterTest extends SavedObjectsBase {

    @Test
    public void testConvertUrlVersion() throws JsonProcessingException {
        // Given a json data with no 'locatorJSON' attribute
        final ObjectNode json = this.loadJsonFile("/data/url/input-001-version-8.8.0.json");

        final UrlConverter converter = new UrlConverter();

        // When calling convert
        final SavedObject converted = converter.convert(new SavedObject(json));

        assertNull(converted.json().get("migrationVersion"));

        final String url = converted.json().at("/attributes/url").asText();
        assertEquals("/test/url", url);

        assertNull(converted.attributeValue("locatorJSON"));
        assertNull(converted.attributeValue("slug"));
    }
    
}
