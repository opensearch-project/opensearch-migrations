package org.opensearch.migrations.transformation.rules;

import java.util.LinkedHashSet;
import java.util.Set;

import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexMappingTypeRemovalWithMergedSupport extends IndexMappingTypeRemoval {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public IndexMappingTypeRemovalWithMergedSupport(MultiTypeResolutionBehavior behavior) {
        super(behavior);
    }

    @Override
    protected void mergePropertiesCheckingConflicts(final Index index, ObjectNode resolvedProperties, String type, JsonNode properties) {
        properties.properties().forEach(propertyEntry -> {
            var fieldName = propertyEntry.getKey();
            var incomingField = propertyEntry.getValue();

            if (resolvedProperties.has(fieldName)) {
                var existingField = resolvedProperties.get(fieldName);

                // If both are objects with "type", try to merge
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
                        return;
                    }
                }

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
