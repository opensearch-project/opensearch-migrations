package org.opensearch.migrations.dashboards.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

/**
 * This class represents the Dashboard object from the OpenSearch API.
 * It contains various properties and methods to access and manipulate the dashboard data.
 */
@Data
public class Dashboard {
    /**
     * Attributes of the Dashboard object.
     * @see Attributes
     */
    @JsonProperty("attributes")
    private Attributes attributes;

    @JsonProperty("coreMigrationVersion")
    private String coreMigrationVersion;

    @JsonProperty("id")
    private String id;

    @JsonProperty("migrationVersion")
    private MigrationVersion migrationVersion;

    @JsonProperty("references")
    private List<References> references;

    @JsonProperty("type")
    private String type;

    @JsonProperty("updated_at")
//    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-ddTHH:mm:ss.SSSZ", timezone = "UTC")
    private String updated_at;

    @JsonProperty("created_at")
//    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-ddTHH:mm:ss.SSSZ", timezone = "UTC")
    private String created_at;

    @JsonProperty("version")
    private String version;

    @JsonProperty("sort")
    private long[] sort;

    @JsonProperty("typeMigrationVersion")
    private String typeMigrationVersion;

    @JsonProperty("managed")
    private boolean managed;

    /**
     * Attributes of the Dashboard object.
     * @see Attributes
     */
    @Data
    public static class Attributes {
        @JsonProperty("description")
        private String description;

        @JsonProperty("hits")
        private int hits;

        @JsonProperty("kibanaSavedObjectMeta")
        private SavedObjectMeta kibanaSavedObjectMeta;

        @JsonProperty("optionsJSON")
        private String optionsJSON;

        @JsonProperty("panelsJSON")
        private String panelsJSON;

        @JsonProperty("locatorJSON")
        private String locatorJSON;

        @JsonProperty("timeRestore")
        private boolean timeRestore;

        @JsonProperty("title")
        private String title;

        // Index-pattern related START
        @JsonProperty("runtimeFieldMap")
        private String runtimeFieldMap;

        @JsonProperty("fieldAttrs")
        private String fieldAttrs;

        @JsonProperty("fieldFormatMap")
        private String fieldFormatMap;

        @JsonProperty("fields")
        private String fields;

        @JsonProperty("sourceFilters")
        private String sourceFilters;

        @JsonProperty("timeFieldName")
        private String timeFieldName;

        @JsonProperty("typeMeta")
        private String typeMeta;
        //Index-patter related END

        @JsonProperty("visualizationType")
        private String visualizationType;

        @JsonProperty("uiStateJSON")
        private String uiStateJSON;

        @JsonProperty("visState")
        private String visState;

        @JsonProperty("version")
        private int version;

        @JsonProperty("url")
        private String url;

        @JsonProperty("accessCount")
        private int accessCount;

        @JsonProperty("accessDate")
        private long accessDate;

        @JsonProperty("createDate")
        private long createDate;

        @JsonProperty("columns")
        private String[] columns;

        @JsonProperty("slug")
        private String slug;

        @JsonProperty("sort")
        private String[][] sort;

    }

    /**
     * This method makes the dashboard compatible with the OpenSearch,
     * It sanitizes the panel JSON, removes incompatible object types, and updates the migration version accordingly.
     * This is tested with kibana v 7.17, and not fully tested with Kibana v8 onwards
     *
     * @throws JsonProcessingException
     * @throws IOException
     */
    public void makeCompatibleToOS() throws JsonProcessingException, IOException {
        switch (type) {
            case "dashboard":
                // TODO: check if the version is greater than 7.9.3, leave the value as is if it is less than this.
                if (migrationVersion == null) {
                    migrationVersion = new MigrationVersion();
                    migrationVersion.setDashboard("7.9.3");
                } else {
                    migrationVersion.setDashboard("7.9.3");
                }
                sanitizePanelJSON();
                // fix some visualization references name
                references = references.stream()
                        .filter(ref -> isCompatibleObjectType(ref.getType()))
                        .map(ref -> {
                            if (ref.getType().equals("visualization")) {
                                ref.setName(getNormalizedVizName(ref.getName()));
                            }
                            return ref;
                        })
                        .collect(Collectors.toList());
                break;
            case "visualization":
                migrationVersion.setVisualization("7.9.3");
                break;
            case "index-pattern":
                migrationVersion.setIndexPattern("7.6.0");
                break;
            case "url":
                sanitizeLocationJSON();
                break;
        }
    }

    private String getNormalizedVizName(String s) {
        int idx = s.indexOf(":");
        if (idx != -1) {
            return s.substring(idx + 1);
        }
        return s;
    }

    public boolean isCompatibleType() {
        return isCompatibleObjectType(type);
    }

    /**
     * This method checks if the object type is compatible with the OpenSearch Dashboards.
     * It excludes the following object types from the dashboard json: "lens", "map", "canvas-workpad", "canvas-element", "graph-workspace", "connector", "rule", "action".
     * @param objectType
     * @return
     */
    private boolean isCompatibleObjectType(String objectType) {
        switch (objectType) {
            case "":
            case "lens":
            case "map":
            case "canvas-workpad":
            case "canvas-element":
            case "graph-workspace":
            case "connector":
            case "rule":
            case "action":
                return false;
            default:
                return true;
        }
    }

    /**
     * This method sanitizes the panel JSON by removing incompatible object types using the isCompatibleObjectType method.
     * It uses the Jackson ObjectMapper to read and write the JSON data.
     * @return
     * @throws JsonProcessingException
     * @throws IOException
     */
    private void sanitizePanelJSON() throws JsonProcessingException, IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, Object>> panels = objectMapper.readValue(attributes.getPanelsJSON(), new TypeReference<List<Map<String, Object>>>() {});

        List<Map<String, Object>> results = panels.stream()
                .filter(panel -> panel.get("type") != null)
                .filter(panel -> isCompatibleObjectType(panel.get("type").toString()))
                .collect(Collectors.toList());

        String resultJson = objectMapper.writeValueAsString(results);
        attributes.setPanelsJSON(resultJson);
    }

    /**
     * This method sanitizes the location JSON by removing the "url" field from the "state" object.
     * It uses the Jackson ObjectMapper to read and write the JSON data.
     * @return
     * @throws JsonProcessingException
     * @throws IOException
     */
    private void sanitizeLocationJSON() throws JsonProcessingException, IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        if (attributes.getLocatorJSON() == null || attributes.getLocatorJSON().isEmpty()) {
            return;
        }

        Map<String, Object> locationJson = objectMapper.readValue(attributes.getLocatorJSON(), new TypeReference<Map<String, Object>>() {});

        if (locationJson.get("state") != null) {
            Map<String, Object> state = (Map<String, Object>) locationJson.get("state");
            if (state.get("url") != null) {
                attributes.setUrl(state.get("url").toString());
                attributes.setLocatorJSON("");
                attributes.setSlug("");
            }
        }
    }
}
