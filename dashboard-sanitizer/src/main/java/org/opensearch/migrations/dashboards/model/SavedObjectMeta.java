package org.opensearch.migrations.dashboards.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class SavedObjectMeta {
    @JsonProperty("searchSourceJSON")
    private String searchSourceJSON;
}
