package org.opensearch.migrations.transformation.rules;

import java.util.Map.Entry;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.Version.Product;
import org.opensearch.migrations.transformation.Version;
import org.opensearch.migrations.transformation.VersionRange;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class IndexMappingTypeRemoval implements TransformationRule<Index> {

    private final VersionRange anyElasticsearch = new VersionRange(
        new Version(Product.ELASTICSEARCH, 0, 0, 0),
        new Version(Product.ELASTICSEARCH, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
    ); 

    private final VersionRange anyOpenSearch = new VersionRange(
        new Version(Product.OPENSEARCH, 0, 0, 0),
        new Version(Product.OPENSEARCH, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
    ); 

    @Override
    public VersionRange supportedSourceVersionRange() {
        return anyElasticsearch;
    }

    @Override
    public VersionRange supportedTargetVersionRange() {
        return anyOpenSearch;
    }

    @SuppressWarnings("java:S125") // False positive for commented out code, comments include json snippets for clarify
    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.raw().get("mappings");
        // Detect multiple type mappings, eg: {mappings: { foo: {...}, bar: {...} } } 
        if (mappingNode.size() > 1) {
            return CanApplyResult.UNSUPPORTED;
        }

        // Detect no intermediate type, eg: { mappings: { properties: {...} } } 
        if (mappingNode.has("properties")) {
            return CanApplyResult.NO;
        }

        //  There is a type under mappings, e.g. { mappings: { foobar: {...} } } 
        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var mappingsNode = (ObjectNode)index.raw().get("mappings");
        final var typeName = mappingsNode.properties().stream().map(Entry::getKey).findFirst().orElseThrow();
        final var typeNode = mappingsNode.get(typeName);
        
        mappingsNode.remove(typeName);
        typeNode.fields().forEachRemaining(node -> mappingsNode.set(node.getKey(), node.getValue()));

        return true;
    }
}
