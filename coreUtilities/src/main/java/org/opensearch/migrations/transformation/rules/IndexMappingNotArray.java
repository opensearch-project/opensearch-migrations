package org.opensearch.migrations.transformation.rules;

import java.util.Map.Entry;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.Version.Product;
import org.opensearch.migrations.transformation.Version;
import org.opensearch.migrations.transformation.VersionRange;
import org.opensearch.migrations.transformation.entity.Index;


public class IndexMappingNotArray implements TransformationRule<Index> {

    @Override
    public VersionRange supportedSourceVersionRange() {
        return new VersionRange(
            new Version(Product.ELASTICSEARCH, 0, 0, 0),
            new Version(Product.ELASTICSEARCH, 7, 0, 0)
        );
    }

    @Override
    public VersionRange supportedTargetVersionRange() {
        return new VersionRange(
            new Version(Product.OPENSEARCH, 0, 0, 0),
            new Version(Product.OPENSEARCH, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
        );
    }

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.raw().get("mappings");
        if (mappingNode.isNull() || mappingNode.size() > 1) {
            return CanApplyResult.UNSUPPORTED;
        }

        if (mappingNode.isObject()) {
            return CanApplyResult.NO;
        }

        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var mappingsNode = index.raw().get("mappings");
        final var inner = mappingsNode.get(0);
       
        index.raw().set("mappings", inner);

        return true;
    }
}
