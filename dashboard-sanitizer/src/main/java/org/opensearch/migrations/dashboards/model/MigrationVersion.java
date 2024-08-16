package org.opensearch.migrations.dashboards.model;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;


/**
 * This class represents the MigrationVersion object, which contains the versions of various components
 * of the OpenSearch Dashboards. It is used to ensure compatibility with different versions of the components.
 */
@Data
public class MigrationVersion {
    @JsonProperty("lens")
    private String lens; // should be removed for OpenSearch

    @JsonProperty("dashboard")
    private String dashboard; // the version should be 7.9.3 or less

    @JsonProperty("index-pattern")
    private String indexPattern; // the version should be 7.6.0 or less

    @JsonProperty("visualization")
    private String visualization; // the version should be 7.9.3 or less
}
