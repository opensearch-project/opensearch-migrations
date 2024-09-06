package org.opensearch.migrations.dashboards.savedobjects;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.ToString;

@ToString(includeFieldNames = true)
public class Reference {

    @ToString.Exclude
    private ObjectNode jsonData;

    public Reference(ObjectNode jsonData) {
        this.jsonData = jsonData;
    }

    public String getId() {
        return jsonData.get("id").asText();
    }

    public String getName() {
        return jsonData.get("name").asText();
    }

    public void setName(String name) {
        jsonData.put("name", name);
    }

    public String getType() {
        return jsonData.get("type").asText();
    }

    public ObjectNode toJson() {
        return jsonData;
    }

}