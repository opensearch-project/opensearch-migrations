package org.opensearch.migrations.transformation;

import org.opensearch.migrations.transformation.entity.Entity;

/**
 * Describes how to an entity is transformed from one version to another.
 */
public interface TransformationRule<T extends Entity> {
    VersionRange supportedSourceVersionRange();
    VersionRange supportedTargetVersionRange();
    boolean canApply(T entity);
    boolean applyTransformation(T entity);
}