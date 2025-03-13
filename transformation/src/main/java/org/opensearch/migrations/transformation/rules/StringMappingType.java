package org.opensearch.migrations.transformation.rules;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports transformation of the mapping of 'string' -> 'text' that were changed between ES 2 to ES 5
 *
 * Example:
 * Starting state (ES 2):
 *  "mappings": {
 *      "properties": {
 *          "field1": {
 *             "type": "string"
 *          }
 *       }
 *  }
 *
 * Ending state (ES 5):
*  "mappings": {
 *     "properties": {
 *        "field1": {
 *           "type": "text",
 *           "fields": {
 *              "keyword": {
 *                 "type": "keyword",
 *                 "ignore_above": 256
 *              }
 *           }
 *        }
 *     }
 *  }
  */
@Slf4j
@AllArgsConstructor
public class StringMappingType implements TransformationRule<Index> {

    public static final String MAPPINGS_KEY = "mappings";
    public static final String PROPERTIES_KEY = "properties";

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.getRawJson().get(MAPPINGS_KEY);
        log.atInfo().setMessage(index.getName() + "Mapping node " + (mappingNode != null ? mappingNode.size() : -1)).log();

        if (mappingNode == null) {
            return CanApplyResult.NO;
        }

        var hasStringType = new AtomicReference<>(CanApplyResult.NO);
        findStringFieldType(mappingNode, ignored -> hasStringType.set(CanApplyResult.YES));

        return hasStringType.get();
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }
        log.atDebug().setMessage(index.getName() + " Applying transformation").log();

        final var mappingNode = index.getRawJson().get(MAPPINGS_KEY);

        var anyChanges = new AtomicBoolean(false);
        findStringFieldType(mappingNode, matchedNode -> {
            matchedNode.put("type", "text")
                .withObject("fields")
                    .withObject("keyword")
                        .put("type", "keyword")
                        .put("ignore_above", 256);
            anyChanges.set(true);
        });

        return anyChanges.get();
    }

    private void findStringFieldType(final JsonNode mappingNode, Consumer<ObjectNode> whenMatched) {
        var properties = mappingNode.get(PROPERTIES_KEY);
        properties.fields().forEachRemaining(mappingFields -> {
            log.atInfo().setMessage("Inspecting field " + mappingFields.getKey() + " " + mappingFields.getValue()).log();
            var mappingFieldType = mappingFields.getValue().get("type");
            if (mappingFieldType != null && "string".equals(mappingFieldType.asText())) {
                whenMatched.accept((ObjectNode)mappingFields.getValue());
            }
        });
    }
}
