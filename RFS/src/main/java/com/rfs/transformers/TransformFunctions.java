package com.rfs.transformers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;

public class TransformFunctions {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static Transformer getTransformer(
        Version sourceVersion,
        Version targetVersion,
        int dimensionality
    ) {
        if (VersionMatchers.isOS_2_X.test(targetVersion)) {
            if (VersionMatchers.isES_6_8.test(sourceVersion)) {
                return new Transformer_ES_6_8_to_OS_2_11(dimensionality);
            }
            if (VersionMatchers.equalOrGreaterThanES_7_10.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(dimensionality);
            }
        }

        throw new IllegalArgumentException("Unsupported transformation requested");
    }

    /* Turn dotted index settings into a tree, will start like:
     * {"index.number_of_replicas":"1","index.number_of_shards":"5","index.version.created":"6082499"}
     */
    public static ObjectNode convertFlatSettingsToTree(ObjectNode flatSettings) {
        ObjectNode treeSettings = mapper.createObjectNode();

        flatSettings.fields().forEachRemaining(entry -> {
            String[] parts = entry.getKey().split("\\.");
            ObjectNode current = treeSettings;

            for (int i = 0; i < parts.length - 1; i++) {
                if (!current.has(parts[i])) {
                    current.set(parts[i], mapper.createObjectNode());
                }
                current = (ObjectNode) current.get(parts[i]);
            }

            current.set(parts[parts.length - 1], entry.getValue());
        });

        return treeSettings;
    }

    /**
     * If the object has mappings, then we need to ensure they aren't burried underneath an intermediate levels.
     * This can show up a number of ways:
     * - [{"_doc":{"properties":{"address":{"type":"text"}}}}]
     * - [{"doc":{"properties":{"address":{"type":"text"}}}}]
     * - [{"audit_message":{"properties":{"address":{"type":"text"}}}}]
     */
    public static void removeIntermediateMappingsLevels(ObjectNode root) {
        if (root.has("mappings")) {
            try {
                ArrayNode mappingsList = (ArrayNode) root.get("mappings");
                root.set("mappings", getMappingsFromBeneathIntermediate(mappingsList));
            } catch (ClassCastException e) {
                // mappings isn't an array
                return;
            }
        }
    }

    // Extract the mappings from their single-member list, will start like:
    // [{"_doc":{"properties":{"address":{"type":"text"}}}}]
    public static ObjectNode getMappingsFromBeneathIntermediate(ArrayNode mappingsRoot) {
        ObjectNode actualMappingsRoot = (ObjectNode) mappingsRoot.get(0);
        if (actualMappingsRoot.has("_doc")) {
            return (ObjectNode) actualMappingsRoot.get("_doc").deepCopy();
        } else if (actualMappingsRoot.has("doc")) {
            return (ObjectNode) actualMappingsRoot.get("doc").deepCopy();
        } else if (actualMappingsRoot.has("audit_message")) {
            return (ObjectNode) actualMappingsRoot.get("audit_message").deepCopy();
        } else {
            throw new IllegalArgumentException("Mappings root does not contain one of the expected keys");
        }
    }

    /**
     * If the object has settings, then we need to ensure they aren't burried underneath an intermediate key.
     * As an example, if we had settings.index.number_of_replicas, we need to make it settings.number_of_replicas.
     */
    public static void removeIntermediateIndexSettingsLevel(ObjectNode root) {
        // Remove the intermediate key "index" under "settings", will start like:
        // {"index":{"number_of_shards":"1","number_of_replicas":"1"}}
        if (root.has("settings")) {
            ObjectNode settingsRoot = (ObjectNode) root.get("settings");
            if (settingsRoot.has("index")) {
                ObjectNode indexSettingsRoot = (ObjectNode) settingsRoot.get("index");
                settingsRoot.setAll(indexSettingsRoot);
                settingsRoot.remove("index");
                root.set("settings", settingsRoot);
            }
        }
    }

    /**
     * If allocation awareness is enabled, we need to ensure that the number of copies of our data matches the dimensionality.
     * As a specific example, if you spin up a cluster spread across 3 availability zones and your awareness attribute is "zone",
     * then the dimensionality would be 3.  This means you need to ensure the number of total copies is a multiple of 3, with
     * the minimum number of replicas being 2.
     */
    public static void fixReplicasForDimensionality(ObjectNode root, int dimensionality) {
        if (root.has("settings")) {
            ObjectNode settingsRoot = (ObjectNode) root.get("settings");
            if (settingsRoot.has("number_of_replicas")) {
                // dimensionality must be at least 1
                dimensionality = Math.max(dimensionality, 1);
                // If the total number of copies requested in the original settings is not a multiple of the
                // dimensionality, then up it to the next largest multiple of the dimensionality.
                int numberOfCopies = settingsRoot.get("number_of_replicas").asInt() + 1;
                int remainder = numberOfCopies % dimensionality;
                int newNumberOfCopies = (remainder > 0)
                    ? (numberOfCopies + dimensionality - remainder)
                    : numberOfCopies;
                int newNumberOfReplicas = newNumberOfCopies - 1;
                settingsRoot.put("number_of_replicas", newNumberOfReplicas);
            }
        }
    }

}
