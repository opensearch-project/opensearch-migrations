package org.opensearch.migrations.dashboards.savedobjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.ToString;

public class Dashboard extends SavedObject {

    @Getter
    private List<Panel> panels = new ArrayList<>();
 
    public Dashboard(ObjectNode json) {
        super(json);

        this.extractPanel();
    }

    private void extractPanel() {
        final JsonNode panelsString = attributes().get("panelsJSON");

        if (panelsString == null) {
            return;
        }

        try {
            SavedObject.objectMapper.readTree(panelsString.textValue()).forEach(panel -> {
                this.panels.add(new Panel(this, (ObjectNode) panel));
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to panelsJSON", e);
        }
    }

    @ToString
    public static class Panel {
        @ToString.Exclude
        @Getter
        private final ObjectNode json;
        
        @ToString.Exclude
        @Getter
        private final Dashboard dashboard;

        @Getter
        private String panelIndex;

        @Getter
        private String objectType;

        @Getter
        private String title;

        public Panel(Dashboard dashboard, ObjectNode json) {
            this.dashboard = dashboard;
            this.json = json;

            this.objectType = Optional.ofNullable(json.get("type")).map(JsonNode::asText).orElse("");
            this.panelIndex = json.get("panelIndex").asText();
            this.title = Optional.ofNullable(json.get("title")).map(JsonNode::asText).orElse(null);
        }

        public ObjectNode embeddableConfig() {
            return (ObjectNode) json.get("embeddableConfig");
        }

        public boolean hasSavedVis() {
            return this.embeddableConfig() != null && this.embeddableConfig().has("savedVis");
        }

        public String fieldValue(String at, String fieldName) {
            return Optional.ofNullable(this.json.at(at).get(fieldName)).map(JsonNode::asText).orElse(null);
        }

    }

}
