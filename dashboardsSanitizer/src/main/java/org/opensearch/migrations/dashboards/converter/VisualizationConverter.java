package org.opensearch.migrations.dashboards.converter;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

// 
// Class Visualization defined the migration for the search object type between ES and OpenSearch
//
// Source ES: https://github.com/elastic/kibana/blob/main/src/plugins/visualizations/server/migrations/visualization_saved_object_migrations.ts
// Source OpenSearch: https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/src/plugins/visualizations/server/saved_objects/visualization_migrations.ts


public class VisualizationConverter extends SavedObjectConverter<SavedObject> {

    public VisualizationConverter() {
        super();
        this.addMigration("8.5.0", this::backportRemoveExclamationCircleIcon);
        this.addMigration("8.3.0", this::backportPreserveOldLegendSizeDefaults);
        this.addMigration("8.1.0", this::backportUpdatePieVisApi);
        this.addMigration("7.17.0", this::backportAddDropLastBucketIntoTSVBModel714Above);
        this.addMigration("7.14.0", this::backportNothing);
        this.addMigration("7.13.0", this::backportNothing);
        this.addMigration("7.12.0", this::backportNothing);
        this.addMigration("7.11.0", this::backportNothing);
        this.addMigration("7.10.0", this::backportNothing);
    }

    public void backportRemoveExclamationCircleIcon(SavedObject savedObject) {
        // this migration is irreversible as we don't know which fa-exclamation-triangle has to be migrated back to fa-exclamation-circle
    }
    

    public void backportPreserveOldLegendSizeDefaults(SavedObject savedObject) {
        final ObjectNode visState = savedObject.attributeValueAsJson("visState");

        if (visState != null) {
            final ObjectNode paramsNode = visState.withObject("params");

            if (paramsNode.has("legendSize")) {
                final String newValue = convertLegendSize(paramsNode.get("legendSize").asText());
                
                if (newValue != null) {
                    paramsNode.put("legendSize", newValue);
                } else {
                    paramsNode.remove("legendSize");
                }
            }
            savedObject.stringifyAttribute("visState", visState);
        }
    }

    private String convertLegendSize(String size) {
        final Map<String, String> sizeMap = new HashMap<>() {
            {
                put("auto", null);
                put("small", "80");
                put("medium", "130");
                put("large", "180");
                put("xlarge", "230");
            }
        };

        return sizeMap.getOrDefault(size, size);
    }

    private void backportUpdatePieVisApi(SavedObject savedObject) {
        final ObjectNode visState = savedObject.attributeValueAsJson("visState");

        if (visState != null && visState.has("type") && "pie".equals(visState.get("type").asText())) {
            final ObjectNode paramsNode = visState.withObject("params");

            if (paramsNode.has("legendDisplay") && "show".equals(paramsNode.get("legendDisplay").asText())) {
                paramsNode.put("addLegend", true);
            } else {
                paramsNode.put("addLegend", false);
            }

            savedObject.stringifyAttribute("visState", visState);
        }
    }

    private void backportAddDropLastBucketIntoTSVBModel714Above(SavedObject savedObject) {
        // this migration just ensured a field is present, no need to backport
    }
}
