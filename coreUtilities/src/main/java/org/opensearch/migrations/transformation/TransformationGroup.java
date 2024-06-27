package org.opensearch.migrations.transformation;

import org.opensearch.migrations.transformation.entity.Entity;

public interface TransformationGroup {
    <T extends Entity> TransformationRule<T> getRules(final Class<T> entityType);
}
