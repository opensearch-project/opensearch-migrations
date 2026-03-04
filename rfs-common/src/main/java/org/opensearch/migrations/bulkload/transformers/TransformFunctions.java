package org.opensearch.migrations.bulkload.transformers;

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

    /* Turn dotted index settings into a tree, will start like:
     * {"index.number_of_replicas":"1","index.number_of_shards":"5","index.version.created":"6082499"}
     * 
     * Strategy: First pass collects all keys and extracts their prefixes (e.g., "a.b.c" yields prefixes "a" and "a.b").
     * Second pass performs bidirectional conflict detection: checks whether each key exists in the prefix set (meaning
     * other keys would nest under it) and whether any of its own prefixes exist in the key set (meaning it would nest
     * under another value). Keys with conflicts remain flat; conflict-free keys are nested into tree structure.
     * Complexity is O(N) where N is the total number of keys and prefixes across all keys.
     * 
     * Special handling for conflicting keys: When a scalar value like "knn": "true" exists
     * alongside nested properties like "knn.space_type": "l2", we keep the settings flat
     * to preserve both values. This means the output will have both "knn": "true" and
     * "knn.space_type": "l2" as separate flat keys rather than nesting space_type under knn.
     */
    public static ObjectNode convertFlatSettingsToTree(ObjectNode flatSettings) {
        ObjectNode treeSettings = mapper.createObjectNode();
        
        // Collect all keys and build prefix map upfront for efficient conflict detection
        ConflictDetectionContext context = buildConflictDetectionContext(flatSettings);
        
        // Build the tree
        flatSettings.properties().forEach(entry -> {
            String key = entry.getKey();
            
            if (hasKeyConflict(key, context)) {
                treeSettings.set(key, entry.getValue());
            } else {
                buildNestedStructure(treeSettings, key, entry.getValue());
            }
        });

        return treeSettings;
    }

    /**
     * Builds a context containing all keys and their prefixes for efficient conflict detection.
     */
    private static ConflictDetectionContext buildConflictDetectionContext(ObjectNode flatSettings) {
        java.util.Set<String> allKeys = new java.util.HashSet<>();
        java.util.Set<String> allPrefixes = new java.util.HashSet<>();
        
        flatSettings.fieldNames().forEachRemaining(key -> {
            allKeys.add(key);
            collectPrefixes(key, allPrefixes);
        });
        
        return new ConflictDetectionContext(allKeys, allPrefixes);
    }

    /**
     * Collects all prefixes for a given dotted key.
     */
    private static void collectPrefixes(String key, java.util.Set<String> allPrefixes) {
        String[] parts = key.split("\\.");
        for (int i = 1; i < parts.length; i++) {
            String prefix = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i));
            allPrefixes.add(prefix);
        }
    }

    /**
     * Checks if a key has conflicts with other keys (either as a prefix or having a prefix that exists as a key).
     */
    private static boolean hasKeyConflict(String key, ConflictDetectionContext context) {
        // Check if this key is a prefix for other keys (reverse conflict)
        if (context.allPrefixes.contains(key)) {
            return true;
        }
        
        // Check if any prefix of this key exists as a standalone key (conflict)
        String[] parts = key.split("\\.");
        for (int i = 1; i < parts.length; i++) {
            String prefix = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i));
            if (context.allKeys.contains(prefix)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Builds a nested structure in the tree for a given key-value pair.
     */
    private static void buildNestedStructure(ObjectNode treeSettings, String key, com.fasterxml.jackson.databind.JsonNode value) {
        String[] parts = key.split("\\.");
        ObjectNode current = treeSettings;
        
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.has(parts[i])) {
                current.set(parts[i], mapper.createObjectNode());
            }
            current = (ObjectNode) current.get(parts[i]);
        }
        current.set(parts[parts.length - 1], value);
    }

    /**
     * Context for conflict detection containing all keys and prefixes.
     */
    private static class ConflictDetectionContext {
        final java.util.Set<String> allKeys;
        final java.util.Set<String> allPrefixes;

        ConflictDetectionContext(java.util.Set<String> allKeys, java.util.Set<String> allPrefixes) {
            this.allKeys = allKeys;
            this.allPrefixes = allPrefixes;
        }
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
