package org.opensearch.migrations.transformation;

/**
 * Describes how to an entity is transformed from one version to another.
 */
interface TransformationRule<T extends Entity> {
    Version minSupportedSourceVersion();
    Version minRequiredTargetVersion();
    boolean canApply(T entity);
    boolean applyTransformation(T entity);
}