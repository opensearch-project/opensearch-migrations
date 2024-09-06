package org.opensearch.migrations.dashboards.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import org.opensearch.migrations.dashboards.Sanitizer;
import org.opensearch.migrations.dashboards.savedobjects.Dashboard;
import org.opensearch.migrations.dashboards.savedobjects.Reference;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;
import org.opensearch.migrations.dashboards.savedobjects.Dashboard.Panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class DashboardConverter extends SavedObjectConverter<Dashboard> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private PanelConverter panelConverter;

    public DashboardConverter() {
        super();
        this.dynamic = DynamicMapping.STRICT;
        this.allowedAttributes = List.of(
                "description",
                "hits",
                "kibanaSavedObjectMeta",
                "optionsJSON",
                "panelsJSON",
                "refreshInterval",
                "timeFrom",
                "timeRestore",
                "timeTo",
                "title",
                "version"
        );

        this.addMigration("7.14.0", this::backport7_17_3To7_14);
        this.addMigration("7.11.0", this::backport7_14To7_11);
        this.addMigration("7.9.3", this::backport7_11To7_9_3);

        panelConverter = new PanelConverter();
    }

    @Override
    public SavedObject convert(Dashboard dashboard) {
        super.convert(dashboard);

        final List<Panel> newPanels = new ArrayList<>();
        for (Panel panel : dashboard.getPanels()) {
            if (panelConverter.isCompatible(panel)) {
                newPanels.add(panel);
            } else if (panelConverter.isConvertible(panel)) {
                log.debug("Panel is convertible {}", panel);
                panelConverter.convert(panel);
                // log.debug("New saved objects: {}", this.getNewSavedObjects().stream().map(SavedObject::getId).toList());
                newPanels.add(panel);
            } else {
                log.warn("Dashboard: {}, panel: {} is not compatible and cannot be converted", dashboard.getId(), panel.getPanelIndex());
            }
        }

        if (!newPanels.isEmpty()) {
            try {
                String panelsJson = objectMapper.writeValueAsString(objectMapper.createArrayNode()
                    .addAll(newPanels.stream().map(Panel::getJson).collect(Collectors.toList()))
                    );
                dashboard.attributes().put("panelsJSON", panelsJson);
            } catch (JsonProcessingException e) {
                log.error("Error converting panels to JSON", e);
            }
        }

        return dashboard;
    }

    private void backport7_17_3To7_14(Dashboard dashboard) {
        dashboard.getPanels().forEach(panelConverter::backport7_17_3To7_14);
    }

    private void backport7_14To7_11(Dashboard dashboard) {
    }

    private void backport7_11To7_9_3(Dashboard dashboard) {
        dashboard.getPanels().forEach(panelConverter::backport7_11To7_9_3);
    }

    public static class PanelConverter {

        private final Set<String> incompatibleTypes = Set.of(
            "lens",
            "map",
            "canvas-workpad",
            "canvas-element",
            "graph-workspace",
            "connector",
            "rule",
            "action"
        );

        public PanelConverter() {
        }

        public boolean isCompatible(Panel panel) {
            final String objectType = panel.getObjectType();
            
            if (incompatibleTypes.contains(objectType)) {
                return false;
            } else if ("visualization".equals(objectType) && panel.hasSavedVis()) {
                return false;
            } else {
                return true;
            }
        }

        public boolean isConvertible(Panel panel) {
            return panel.hasSavedVis();
        }

        public void convert(Panel panel) {
            if (panel.hasSavedVis()) {

                final JsonNode searchSource = panel.embeddableConfig().at("/savedVis/data/searchSource");

                List<Reference> references = new ArrayList<>();
                searchSource.findValues("indexRefName").forEach(field -> {
                    String refName = field.asText();
                    Reference ref = panel.getDashboard().findReference(refName);
                    references.add(
                        new Reference(objectMapper.createObjectNode()
                            .put("id", ref.getId())
                            .put("name", refName)
                            .put("type", "index-pattern"))
                    );
                });

                final String title = Optional.ofNullable(panel.fieldValue("/embeddableConfig/savedVis", "title")).orElse("");
                final String description = Optional.ofNullable(panel.fieldValue("/embeddableConfig/savedVis", "description")).orElse("");
                final String type = Optional.ofNullable(panel.fieldValue("/embeddableConfig/savedVis", "type")).orElse("");
                
                SavedObject visualization;
                try {
                    visualization = new SavedObject(
                        ((ObjectNode)((ObjectNode)objectMapper.createObjectNode()
                            .put("id", UUID.randomUUID().toString())
                            .put("updated_at", Optional.ofNullable(panel.getDashboard().json().get("updated_at")).map(JsonNode::textValue).orElse(null))
                            .put("version", Optional.ofNullable(panel.getDashboard().json().get("version")).map(JsonNode::textValue).orElse(null))
                            .put("type", "visualization")
                            .set("migrationVersion", objectMapper.createObjectNode().put("visualization", panel.getDashboard().getExportedVersion().getVersion())))
                            .set("references", objectMapper.createArrayNode().addAll(references.stream().map(Reference::toJson).collect(Collectors.toList())))
                            )
                            .set("attributes", ((ObjectNode)objectMapper.createObjectNode()
                                .put("title", title)
                                .put("description", description)
                                .put("uiStateJSON", Optional.ofNullable(panel.fieldValue("/embeddableConfig/savedVis", "uiStateJSON")).orElse("{}"))
                                .put("version", Optional.ofNullable(panel.embeddableConfig().get("savedVis").get("version")).map(JsonNode::numberValue).orElse(1).intValue())
                                .set("kibanaSavedObjectMeta", objectMapper.createObjectNode()
                                    .put("searchSourceJSON", objectMapper.writeValueAsString(searchSource))))
                                .put("visState", objectMapper.writeValueAsString(
                                    ((ObjectNode)objectMapper.createObjectNode()
                                    .put("title", title)
                                    .put("description", description)
                                    .put("type", type)
                                    .set("params", panel.embeddableConfig().get("savedVis").get("params")))
                                    .set("aggs", panel.embeddableConfig().get("savedVis").get("data").get("aggs"))
                                    )
                                )
                            )
                    );
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to convert panel to visualization", e);
                }

                panel.getJson().remove("type");
                panel.getJson().set("embeddableConfig", objectMapper.createObjectNode());
                panel.getJson().put("panelRefName", "panel_" + panel.getJson().get("panelIndex").asText());
                panel.getDashboard().addReference(
                    new Reference(objectMapper.createObjectNode()
                            .put("id", visualization.getId())
                            .put("name", panel.getJson().get("panelRefName").asText())
                            .put("type", "visualization")
                            )
                    );
              
                Sanitizer.getInstance().addNewObjectToQueue(visualization);
            } 
        }

        public void backport7_17_3To7_14(Panel panel) {
            ObjectNode embeddableConfig = panel.embeddableConfig();

            if (embeddableConfig == null) {
                embeddableConfig = objectMapper.createObjectNode();
                panel.getJson().set("embeddableConfig", embeddableConfig);
            } else {
                Optional.ofNullable(embeddableConfig.get("hidePanelTitles")).ifPresent(hidePanelTiles -> {
                    if (hidePanelTiles.asBoolean()) {
                        panel.getJson().put("title", "");
                    }
                });
            }

            embeddableConfig.put("title", Optional.ofNullable(panel.getTitle()).orElse(""));
            panel.getJson().remove("title");
        }

        public void backport7_11To7_9_3(Panel panel) {
            Optional.ofNullable(panel.getJson().get("panelRefName"))
                .map(JsonNode::asText)
                .ifPresent(panelRefName -> {
                    Reference reference = panel.getDashboard().findReference(panelRefName);
                    String panelIndex = panel.getPanelIndex();

                    if (reference == null) {
                        panelRefName = panelIndex + ":" + panelRefName;
                        reference = panel.getDashboard().findReference(panelRefName);
                    }

                    if (reference != null) {
                        panel.getJson().put("panelRefName", panelRefName);
                    } else {
                        log.warn("Panel reference {} not found in dashboard {}", panelIndex, panel.getDashboard().getId());
                        panel.getJson().remove("panelRefName");
                    }
                });

            panel.getJson().findParents("indexRefName").forEach(parent -> {
                String refName = parent.get("indexRefName").asText();
                Reference ref = panel.getDashboard().findReference(refName);
                String panelIndex = panel.getPanelIndex();

                if (ref == null) {
                    refName = panelIndex + ":" + refName;
                    ref = panel.getDashboard().findReference(refName);

                    if (ref != null) {
                        ((ObjectNode)parent).put("indexRefName", refName);
                    } else {
                        log.warn("Index reference {} not found in dashboard {}", refName, panel.getDashboard().getId());
                    }
                }
            });

            panel.getJson().remove("title");
        }
    }
}

