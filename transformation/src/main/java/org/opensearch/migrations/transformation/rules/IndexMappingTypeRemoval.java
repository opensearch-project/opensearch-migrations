package org.opensearch.migrations.transformation.rules;

import java.util.Map.Entry;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.CanApplyResult.Unsupported;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
public class IndexMappingTypeRemoval implements TransformationRule<Index> {

    public static final String MAPPINGS_KEY = "mappings";

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.getRawJson().get(MAPPINGS_KEY);

        if (mappingNode == null) {
            return CanApplyResult.NO;
        }


        // Detect unsupported multiple type mappings:
        // 1. <pre>{"mappings": [{ "foo": {...} }, { "bar": {...} }]}</pre>
        // 2. <pre>{"mappings": [{ "foo": {...}, "bar": {...}  }]}</pre>
        if (mappingNode.isArray() && (mappingNode.size() > 1 || mappingNode.get(0).size() > 1)) {
            return new Unsupported("Multiple mapping types are not supported");
        }

        // Check for absence of intermediate type node
        // 1. <pre>{"mappings": {"properties": {...} }}</pre>
        if (mappingNode.isObject() && mappingNode.get("properties") != null) {
            return CanApplyResult.NO;
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
        // Handle array case
        if (mappingsNode.isArray()) {
            final var mappingsInnerNode = (ObjectNode) mappingsNode.get(0);

            final var typeName = mappingsInnerNode.properties().stream().map(Entry::getKey).findFirst().orElseThrow();
            final var typeNode = mappingsInnerNode.get(typeName);

            mappingsInnerNode.remove(typeName);
            typeNode.fields().forEachRemaining(node -> mappingsInnerNode.set(node.getKey(), node.getValue()));
            index.getRawJson().set(MAPPINGS_KEY, mappingsInnerNode);
        }

        if (mappingsNode.isObject()) {
            var mappingsObjectNode = (ObjectNode) mappingsNode;
            var typeNode = mappingsNode.fields().next();
            var typeNodeChildren = typeNode.getValue().fields();
            // Check if the type node is empty, then there is nothing to move
            if (typeNodeChildren.hasNext()) {
                var propertiesNode = typeNodeChildren.next();

                mappingsObjectNode.set(propertiesNode.getKey(), propertiesNode.getValue());
            }
            mappingsObjectNode.remove(typeNode.getKey());
        }

        return true;
    }
}
