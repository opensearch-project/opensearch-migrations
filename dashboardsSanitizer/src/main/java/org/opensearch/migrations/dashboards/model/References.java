package org.opensearch.migrations.dashboards.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * This class represents the References object, which contains information about a reference.
 * It is used to store and access reference data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class References {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private String type;
}
