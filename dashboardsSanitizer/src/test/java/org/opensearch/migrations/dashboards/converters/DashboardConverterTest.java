package org.opensearch.migrations.dashboards.converters;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.dashboards.Sanitizer;
import org.opensearch.migrations.dashboards.converter.DashboardConverter;
import org.opensearch.migrations.dashboards.savedobjects.Dashboard;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import static org.junit.jupiter.api.Assertions.*;

public class DashboardConverterTest extends SavedObjectsBase {

    @Test
    public void testConvertDashboardVersion() throws JsonProcessingException {
        // Given a json data with no 'allowNoIndex' attribute
        final ObjectNode json = this.loadJsonFile("/data/dashboard/input-001-version-8.8.0.json");

        final DashboardConverter dashboard = new DashboardConverter();

        // When calling convert
        final SavedObject converted = dashboard.convert(new Dashboard(json));

        // Then the migration version should be set to 7.9.3 only
        assertEquals("7.9.3", converted.json().at("/migrationVersion/dashboard").asText());

        final List<ObjectNode> panels = readPanels(converted.json());
        assertEquals(2, panels.size());

        panels.stream()
            .forEach(panel -> {
                panel.findValuesAsText("panelRefName").stream().forEach(panelRefName -> {
                    assertNotNull(converted.findReference(panelRefName));
                });
        });

        final List<ObjectNode> references = references(json);

        assertEquals(10, references.size());
        assertEquals(2, references.stream().filter(reference -> reference.get("type").asText().equals("visualization")).count());
        assertEquals(2, Sanitizer.getInstance().getQueueSize());
        assertEquals(0, references.stream().filter(reference -> reference.get("type").asText().equals("visualization") && reference.get("name").asText().contains("\"")).count());

        final String visalisationStr = Sanitizer.getInstance().processQueue().split(System.lineSeparator())[0];
        final ObjectNode visalisation = (ObjectNode)objectMapper.readTree(visalisationStr);

        assertEquals("visualization", visalisation.get("type").asText());
        assertEquals("{}", visalisation.at("/attributes/uiStateJSON").asText());
    }
    
    public List<ObjectNode> readPanels(ObjectNode json) throws JsonMappingException, JsonProcessingException {
        ArrayNode panelsJSON = (ArrayNode)objectMapper.readTree(json.at("/attributes/panelsJSON").asText());
        List<ObjectNode> panels = new ArrayList<>();
        for (JsonNode panel : panelsJSON) {
            panels.add((ObjectNode)panel);
        }
        return panels;
    }

    public List<ObjectNode> references(ObjectNode json) throws JsonMappingException, JsonProcessingException {
        ArrayNode referencesNode = (ArrayNode)json.get("references");
        List<ObjectNode> references = new ArrayList<>();
        for (JsonNode reference : referencesNode) {
            references.add((ObjectNode)reference);
        }
        return references;
    }
}
