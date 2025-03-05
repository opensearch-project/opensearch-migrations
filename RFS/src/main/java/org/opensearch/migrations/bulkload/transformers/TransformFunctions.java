package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TransformFunctions {
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final String MAPPINGS_KEY_STR = "mappings";
    public static final String PROPERTIES_KEY_STR = "properties";
    public static final String SETTINGS_KEY_STR = "settings";
    public static final String NUMBER_OF_REPLICAS_KEY_STR = "number_of_replicas";
    public static final String INDEX_KEY_STR = "index";

    private TransformFunctions() {}

    public static Transformer getTransformer(
        Version sourceVersion,
        Version targetVersion,
        int dimensionality,
        MetadataTransformerParams metadataTransformerParams
    ) {
        if (VersionMatchers.isOS_2_X.or(VersionMatchers.isOS_1_X).test(targetVersion)) {
            if (VersionMatchers.isES_5_X.test(sourceVersion)) {
                return new Transformer_ES_5_6_to_OS_2_11(dimensionality, metadataTransformerParams);
            }
            if (VersionMatchers.isES_6_X.test(sourceVersion)) {
                return new Transformer_ES_6_8_to_OS_2_11(dimensionality, metadataTransformerParams);
            }
            if (VersionMatchers.equalOrGreaterThanES_7_10.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(dimensionality);
            }
            if (VersionMatchers.isOS_1_X.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(dimensionality);
            }
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion);
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
     * If the object has mappings, then we need to ensure they aren't burried underneath intermediate levels.
     */
    public static void removeIntermediateMappingsLevels(ObjectNode root) {
        if (root.has(MAPPINGS_KEY_STR)) {
            var val = root.get(MAPPINGS_KEY_STR);
            /**
             * This probably came from a Snapshot
             * There should only be a single member in the list because multi-type mappings were deprecated in ES 6.X and
             * removed in ES 7.X.  This list structure appears to be a holdover from previous versions of Elasticsearch.
             * The exact name of the type can be arbitrarily chosen by the user; the default is _doc.  We need to extract
             * the mappings from beneath this intermediate key regardless of what it is named.
             * - [{"_doc":{"properties":{"address":{"type":"text"}}}}]
             * - [{"doc":{"properties":{"address":{"type":"text"}}}}]
             * - [{"audit_message":{"properties":{"address":{"type":"text"}}}}]
             *
             * It's also possible for this list to be empty, in which case we should set the mappings to an empty node.
             *
             * Finally, it may be possible that the intermediate key is not present, in which case we should just extract the mappings:
             * - [{"properties":{"address":{"type":"text"}}}]
             */
            if (val instanceof ArrayNode) {
                ArrayNode mappingsList = (ArrayNode) val;
                if (mappingsList.size() > 1) {
                    throw new IllegalArgumentException("Mappings list contains more than one member; this is unexpected: " + val.toString());
                } else if (mappingsList.size() == 1) {
                    ObjectNode actualMappingsRoot = (ObjectNode) mappingsList.get(0);
                    root.set(MAPPINGS_KEY_STR, getMappingsFromBeneathIntermediate(actualMappingsRoot));
                } else {
                    root.set(MAPPINGS_KEY_STR, mapper.createObjectNode());
                }
            }

            /**
             * This came from somewhere else (like a REST call to the source cluster).  It should be in a shape like:
             * - {"_doc":{"properties":{"address":{"type":"text"}}}}
             * - {"properties":{"address":{"type":"text"}}}
             */
            else if (val instanceof ObjectNode) {
                root.set(MAPPINGS_KEY_STR, getMappingsFromBeneathIntermediate((ObjectNode) val));
            }

            else {
                throw new IllegalArgumentException("Mappings object is not in the expected shape: " + val.toString());
            }
        }
    }

    /**
     * Extract the mappings from the type dict.  It may be that there is no intermediate type key as well.  So, the
     * input could be:
     * {"_doc":{"properties":{"address":{"type":"text"}}}}
     * {"properties":{"address":{"type":"text"}}}
     *
     * If there is a type key ('_doc', etc), the key name can be arbitrary.  We need to extract the mappings from beneath
     * it regardless of what it is named.
     */
    public static ObjectNode getMappingsFromBeneathIntermediate(ObjectNode mappingsRoot) {
        if (mappingsRoot.size() == 0) {
            return mappingsRoot;
        } else if (mappingsRoot.has(PROPERTIES_KEY_STR)) {
            return mappingsRoot;
        } else if (!mappingsRoot.has(PROPERTIES_KEY_STR)) {
            return (ObjectNode) mappingsRoot.get(mappingsRoot.fieldNames().next()).deepCopy();
        }

        throw new IllegalArgumentException("Mappings object is not in the expected shape: " + mappingsRoot.toString());
    }

    /**
     * If the object has settings, then we need to ensure they aren't burried underneath an intermediate key.
     * As an example, if we had settings.index.number_of_replicas, we need to make it settings.number_of_replicas.
     */
    public static void removeIntermediateIndexSettingsLevel(ObjectNode root) {
        // Remove the intermediate key "index" under "settings", will start like:
        // {"index":{"number_of_shards":"1","number_of_replicas":"1"}}
        if (root.has(SETTINGS_KEY_STR)) {
            ObjectNode settingsRoot = (ObjectNode) root.get(SETTINGS_KEY_STR);
            if (settingsRoot.has(INDEX_KEY_STR)) {
                ObjectNode indexSettingsRoot = (ObjectNode) settingsRoot.get(INDEX_KEY_STR);
                settingsRoot.setAll(indexSettingsRoot);
                settingsRoot.remove(INDEX_KEY_STR);
                root.set(SETTINGS_KEY_STR, settingsRoot);
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
        if (root.has(SETTINGS_KEY_STR)) {
            ObjectNode settingsRoot = (ObjectNode) root.get(SETTINGS_KEY_STR);
            if (settingsRoot.has(NUMBER_OF_REPLICAS_KEY_STR)) {
                // dimensionality must be at least 1
                dimensionality = Math.max(dimensionality, 1);
                // If the total number of copies requested in the original settings is not a multiple of the
                // dimensionality, then up it to the next largest multiple of the dimensionality.
                int numberOfCopies = settingsRoot.get(NUMBER_OF_REPLICAS_KEY_STR).asInt() + 1;
                int remainder = numberOfCopies % dimensionality;
                int newNumberOfCopies = (remainder > 0)
                    ? (numberOfCopies + dimensionality - remainder)
                    : numberOfCopies;
                int newNumberOfReplicas = newNumberOfCopies - 1;
                settingsRoot.put(NUMBER_OF_REPLICAS_KEY_STR, newNumberOfReplicas);
            }
        }
    }

}
