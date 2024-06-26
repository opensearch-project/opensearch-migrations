package org.opensearch.migrations.rules;

import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.Version.Product;
import org.opensearch.migrations.transformation.Version;
import org.opensearch.migrations.transformation.VersionRange;
import org.opensearch.migrations.transformation.entity.Index;

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

    @Override
    public boolean canApply(final Index entity) {
        var mappingNode = entity.raw().get("mappings");
        // Detect multiple type mappings, eg: {mappings: { foo: {...}, bar: {...} } } 
        if (mappingNode.size() > 1) {
            throw new UnsupportedOperationException("Mutliple type mappings are not supported");
        }

        // Detect no intermediate type, eg: { mappings: { properties: {...} } } 
        if (mappingNode.has("properties")) {
            return false;
        }

        // Detect default _doc mappings { mappings: { _doc: {...} } } 
        if (mappingNode.has("_doc")) {
            return false;
        }

        // Detect default _doc mappings { mappings: { foobar: {...} } } 
        return true;
    }

    @Override
    public boolean applyTransformation(Index entity) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'applyTransformation'");
    }

    
}
