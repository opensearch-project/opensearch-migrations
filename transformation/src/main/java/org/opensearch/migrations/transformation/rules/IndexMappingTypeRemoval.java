package org.opensearch.migrations.transformation.rules;

import java.util.LinkedHashSet;
import java.util.Set;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports transformation of the Index Mapping types that were changed from mutliple types to a single type between ES 6 to ES 7
 *
 * Example:
 * Starting state (ES 6):
 * {
 *   "mappings": [
 *     {
 *       "foo": {
 *         "properties": {
 *           "field1": { "type": "text" },
 *           "field2": { "type": "keyword" }
 *         }
 *       }
 *     }
 *   ]
 * }
 *
 * Ending state (ES 7):
 * {
 *   "mappings": {
 *     "properties": {
 *       "field1": { "type": "text" },
 *       "field2": { "type": "keyword" },
 *     }
 *   }
 * }
 */
@Slf4j
@AllArgsConstructor
public class IndexMappingTypeRemoval implements TransformationRule<Index> {
    public enum MultiTypeResolutionBehavior {
        NONE,
        UNION,
        SPLIT
    }

    public static final String PROPERTIES_KEY = "properties";
    public static final String MAPPINGS_KEY = "mappings";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public final MultiTypeResolutionBehavior multiTypeResolutionBehavior;

    // Default with NONE
    public IndexMappingTypeRemoval() {
        this(MultiTypeResolutionBehavior.NONE);
    }

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.getRawJson().get(MAPPINGS_KEY);

        if (mappingNode == null || mappingNode.size() == 0) {
            return CanApplyResult.NO;
        }

        // Check for absence of intermediate type node
        // 1. <pre>{"mappings": {"properties": {...} }}</pre>
        if (mappingNode.isObject() && mappingNode.get(PROPERTIES_KEY) != null) {
            return CanApplyResult.NO;
        }

        // Detect multiple type mappings:
        // 1. <pre>{"mappings": [{ "foo": {...} }, { "bar": {...} }]}</pre>
        // 2. <pre>{"mappings": [{ "foo": {...}, "bar": {...}  }]}</pre>
        if (mappingNode.isArray() && (mappingNode.size() > 1 || mappingNode.get(0).size() > 1)) {
            if (MultiTypeResolutionBehavior.NONE.equals(multiTypeResolutionBehavior)) {
                throw new IllegalArgumentException("No multi type resolution behavior declared, specify --multi-type-behavior to process");
            }
            if (MultiTypeResolutionBehavior.SPLIT.equals(multiTypeResolutionBehavior)) {
                throw new IllegalArgumentException("Split on multiple mapping types is not supported");
            }
            // Support UNION
        }

        // There is a type under mappings
        // 1. <pre>{ "mappings": [{ "foo": {...} }] }</pre>
        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var mappingsNode = index.getRawJson().get(MAPPINGS_KEY);
        final var resolvedMappingsNode = MAPPER.createObjectNode();
        final var resolvedProperties = resolvedMappingsNode.withObject(PROPERTIES_KEY);
        // Handle array case
        if (mappingsNode.isArray()) {
            var mappingsArray = (ArrayNode) mappingsNode;
            if (mappingsArray.size() < 2) {
                final var mappingsInnerNode = (ObjectNode) mappingsArray.get(0);
                var properties = mappingsInnerNode.properties().iterator().next().getValue().get(PROPERTIES_KEY);
                resolvedMappingsNode.set(PROPERTIES_KEY, properties);
            } else if (MultiTypeResolutionBehavior.UNION.equals(multiTypeResolutionBehavior)) {
                mergePropertiesFromMappings(mappingsArray, index, resolvedProperties);
            }
            index.getRawJson().set(MAPPINGS_KEY, resolvedMappingsNode);
        } else if (mappingsNode.isObject()) {
            mergePropertiesFromMappings((ObjectNode)mappingsNode, index, resolvedProperties);
        }
        index.getRawJson().set(MAPPINGS_KEY, resolvedMappingsNode);
        return true;
    }

    private void mergePropertiesFromMappings(ObjectNode mappingsNode, Index index, ObjectNode resolvedProperties) {
        mappingsNode.properties().forEach(typeEntry -> {
            var typeNode = typeEntry.getValue();
            if (typeNode.has(PROPERTIES_KEY)) {
                var propertiesNode = typeNode.get(PROPERTIES_KEY);
                mergePropertiesCheckingConflicts(index, resolvedProperties, typeEntry.getKey(), propertiesNode);
            }
        });
    }

    private void mergePropertiesFromMappings(ArrayNode mappingsArray, Index index, ObjectNode resolvedProperties) {
        mappingsArray.forEach(typeNodeEntry -> {
            var typeNode = typeNodeEntry.properties().iterator().next().getValue();
            if (typeNode.has(PROPERTIES_KEY)) {
                var propertiesNode = typeNode.get(PROPERTIES_KEY);
                mergePropertiesCheckingConflicts(index, resolvedProperties, typeNode.textValue(), propertiesNode);
            }
        });
    }

    private void mergePropertiesCheckingConflicts(final Index index, ObjectNode resolvedProperties, String type, JsonNode properties) {
        properties.properties().forEach(propertyEntry -> {
            var fieldName = propertyEntry.getKey();
            var incomingField = propertyEntry.getValue();

            if (resolvedProperties.has(fieldName)) {
                var existingField = resolvedProperties.get(fieldName);

                // Both should be objects with "type" (example: {"type":"keyword"})
                if (existingField.isObject() && incomingField.isObject()) {
                    var existingType = existingField.get("type");
                    var incomingType = incomingField.get("type");

                    if (existingType != null && incomingType != null && existingType.equals(incomingType)) {
                        // Merge optional keys like "copy_to" if needed
                        var merged = (ObjectNode) existingField.deepCopy();

                        // Merge "copy_to" lists
                        Set<String> copyToMerged = new LinkedHashSet<>();
                        JsonNode ctExisting = existingField.get("copy_to");
                        JsonNode ctIncoming = incomingField.get("copy_to");

                        if (ctExisting != null) {
                            if (ctExisting.isArray()) {
                                ctExisting.forEach(node -> copyToMerged.add(node.asText()));
                            } else {
                                copyToMerged.add(ctExisting.asText());
                            }
                        }

                        if (ctIncoming != null) {
                            if (ctIncoming.isArray()) {
                                ctIncoming.forEach(node -> copyToMerged.add(node.asText()));
                            } else {
                                copyToMerged.add(ctIncoming.asText());
                            }
                        }

                        if (!copyToMerged.isEmpty()) {
                            var mergedArray = MAPPER.createArrayNode();
                            copyToMerged.forEach(mergedArray::add);
                            merged.set("copy_to", mergedArray);
                        }

                        resolvedProperties.set(fieldName, merged);
                        return; // skip error
                    }
                }

                // Conflict remains if types differ or not mergeable
                log.atWarn().setMessage("Conflict during type union with index: {}\n" +
                                "field: {}\n" +
                                "existingFieldType: {}\n" +
                                "type: {}\n" +
                                "secondFieldType: {}")
                        .addArgument(index.getName())
                        .addArgument(fieldName)
                        .addArgument(existingField)
                        .addArgument(type)
                        .addArgument(incomingField)
                        .log();

                throw new IllegalArgumentException("Conflicting definitions for property during union "
                        + fieldName + " (" + existingField + " and " + incomingField + ")" );
            } else {
                resolvedProperties.set(fieldName, incomingField);
            }
        });
    }
}
