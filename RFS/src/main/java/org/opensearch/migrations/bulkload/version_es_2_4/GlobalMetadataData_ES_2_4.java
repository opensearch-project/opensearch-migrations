package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.IndexTemplate;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;

@Data
public class GlobalMetadataData_ES_2_4 implements GlobalMetadata {

    private String clusterUUID;
    private Map<String, String> transientSettings;
    private Map<String, String> persistentSettings;
    private List<IndexMetadata> indices;
    private List<IndexTemplate> templates;
    private Map<String, Object> customs;

    // Standard getters and setters
    public String getClusterUUID() { return clusterUUID; }
    public void setClusterUUID(String clusterUUID) { this.clusterUUID = clusterUUID; }
    public Map<String, String> getTransientSettings() { return transientSettings; }
    public void setTransientSettings(Map<String, String> transientSettings) { this.transientSettings = transientSettings; }
    public Map<String, String> getPersistentSettings() { return persistentSettings; }
    public void setPersistentSettings(Map<String, String> persistentSettings) { this.persistentSettings = persistentSettings; }
    public List<IndexMetadata> getIndices() { return indices; }
    public void setIndices(List<IndexMetadata> indices) { this.indices = indices; }
    public List<IndexTemplate> getTemplatesList() { return templates; }
    public void setTemplates(List<IndexTemplate> templates) { this.templates = templates; }
    public Map<String, Object> getCustoms() { return customs; }
    public void setCustoms(Map<String, Object> customs) { this.customs = customs; }

    /**
     * This is how ES 2.4 snapshots serialize "templates":
     * an array of template objects.
     *
     * Example:
     * "templates": [
     *   { "name": "my_template", "order": 0, ... }
     * ]
     */
    @Override
    public ObjectNode getTemplates() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode templatesNode = mapper.createObjectNode();
        if (templates != null) {
            for (IndexTemplate tmpl : templates) {
                IndexTemplateData_ES_2_4 typed = (IndexTemplateData_ES_2_4) tmpl;
                templatesNode.set(typed.getName(), typed.toObjectNode());
            }
        }
        return templatesNode;
    }

    @Override
    public ObjectNode toObjectNode() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        root.put("cluster_uuid", clusterUUID);

        // Transient settings
        ObjectNode trans = mapper.createObjectNode();
        if (transientSettings != null) {
            transientSettings.forEach(trans::put);
        }
        root.set("transient_settings", trans);

        // Persistent settings
        ObjectNode persist = mapper.createObjectNode();
        if (persistentSettings != null) {
            persistentSettings.forEach(persist::put);
        }
        root.set("persistent_settings", persist);

        // Indices (array)
        ObjectNode indicesNode = mapper.createObjectNode();
        if (indices != null) {
            for (IndexMetadata idx : indices) {
                IndexMetadataData_ES_2_4 typed = (IndexMetadataData_ES_2_4) idx;
                indicesNode.set(typed.getName(), typed.toObjectNode());
            }
        }
        root.set("indices", indicesNode);

        // Templates (array)
        ObjectNode templatesNode = mapper.createObjectNode();
        if (templates != null) {
            for (IndexTemplate tmpl : templates) {
                IndexTemplateData_ES_2_4 typed = (IndexTemplateData_ES_2_4) tmpl;
                templatesNode.set(typed.getName(), typed.toObjectNode());
            }
        }
        root.set("templates", templatesNode);

        // Customs
        ObjectNode customsNode = mapper.createObjectNode();
        if (customs != null) {
            customs.forEach((k, v) -> customsNode.set(k, mapper.valueToTree(v)));
        }
        root.set("customs", customsNode);

        return root;
    }

    @Override
    public JsonPointer getTemplatesPath() {
        return JsonPointer.compile("/templates");
    }

    @Override
    public JsonPointer getIndexTemplatesPath() {
        return JsonPointer.compile("/index_templates");
    }

    @Override
    public JsonPointer getComponentTemplatesPath() {
        return JsonPointer.compile("/component_templates");
    }
}
