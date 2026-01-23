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
    private static final String COPY_TO = "copy_to";

    public IndexMappingTypeRemovalWithMergedSupport(MultiTypeResolutionBehavior behavior) {
        super(behavior);
    }

    @Override
    protected void mergePropertiesCheckingConflicts(final Index index, ObjectNode resolvedProperties, String type, JsonNode properties) {
        properties.properties().forEach(propertyEntry -> {
            var fieldName = propertyEntry.getKey();
            var incomingField = propertyEntry.getValue();

            if (!resolvedProperties.has(fieldName)) {
                resolvedProperties.set(fieldName, incomingField);
                return;
            }

            var existingField = resolvedProperties.get(fieldName);

            if (canMerge(existingField, incomingField)) {
                resolvedProperties.set(fieldName, mergeFields(existingField, incomingField));
                return;
            }

            logFieldConflict(index, fieldName, type, existingField, incomingField);
            throw new IllegalArgumentException("Conflicting definitions for property during union "
                    + fieldName + " (" + existingField + " and " + incomingField + ")");
        });
    }

    private boolean canMerge(JsonNode existingField, JsonNode incomingField) {
        if (!existingField.isObject() || !incomingField.isObject()) return false;

        var existingType = existingField.get("type");
        var incomingType = incomingField.get("type");
        return existingType != null && incomingType != null && existingType.equals(incomingType);
    }

    private ObjectNode mergeFields(JsonNode existingField, JsonNode incomingField) {
        var merged = (ObjectNode) existingField.deepCopy();
        Set<String> copyToMerged = new LinkedHashSet<>();

        extractCopyTo(existingField, copyToMerged);
        extractCopyTo(incomingField, copyToMerged);

        if (!copyToMerged.isEmpty()) {
            var mergedArray = MAPPER.createArrayNode();
            copyToMerged.forEach(mergedArray::add);
            merged.set(COPY_TO, mergedArray);
        }

        return merged;
    }

    private void extractCopyTo(JsonNode field, Set<String> result) {
        JsonNode copyTo = field.get(COPY_TO);
        if (copyTo == null) return;

        if (copyTo.isArray()) {
            copyTo.forEach(node -> result.add(node.asText()));
        } else {
            result.add(copyTo.asText());
        }
    }

    private void logFieldConflict(Index index, String fieldName, String type, JsonNode existing, JsonNode incoming) {
        log.atWarn()
            .setMessage("Conflict during type union with index: {}\nfield: {}\nexistingFieldType: {}\ntype: {}\nsecondFieldType: {}")
            .addArgument(index.getName())
            .addArgument(fieldName)
            .addArgument(existing)
            .addArgument(type)
            .addArgument(incoming)
            .log();
    }
}
