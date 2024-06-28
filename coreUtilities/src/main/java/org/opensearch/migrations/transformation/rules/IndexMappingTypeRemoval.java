package org.opensearch.migrations.transformation.rules;

import java.util.Map.Entry;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.CanApplyResult.Unsupported;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Supports transformation of the Index Mapping types that were changed from mutliple types to a single type between ES 6->7
 */
public class IndexMappingTypeRemoval implements TransformationRule<Index> {

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.raw().get("mappings");

        if (mappingNode == null || mappingNode.isObject()) {
            return CanApplyResult.NO;
        }

        // Detect multiple type mappings, eg:
        // { mappings: [{ foo: {...}}, { bar: {...} }] } } or
        // { mappings: [{ foo: {...}, bar: {...}] } }
        if (mappingNode.size() > 1 || mappingNode.get(0).size() > 1) {
            return new Unsupported("Multiple mapping types are not supported");
        }

        // There is a type under mappings, e.g. { mappings: [{ foobar: {...} }] }
        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var mappingsNode = index.raw().get("mappings");
        final var mappingsInnerNode = (ObjectNode) mappingsNode.get(0);

        final var typeName = mappingsInnerNode.properties().stream().map(Entry::getKey).findFirst().orElseThrow();
        final var typeNode = mappingsInnerNode.get(typeName);

        mappingsInnerNode.remove(typeName);
        typeNode.fields().forEachRemaining(node -> mappingsInnerNode.set(node.getKey(), node.getValue()));
        index.raw().set("mappings", mappingsInnerNode);

        return true;
    }
}
