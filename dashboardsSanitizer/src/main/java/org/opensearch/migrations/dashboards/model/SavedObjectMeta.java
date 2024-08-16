package org.opensearch.migrations.dashboards.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * This class represents the SavedObjectMeta object, which contains metadata
 * about a saved object in the OpenSearch Dashboards. It is used to store
 * information such as the search source JSON.
 */
@Data
public class SavedObjectMeta {
    @JsonProperty("searchSourceJSON")
    private String searchSourceJSON;
}
