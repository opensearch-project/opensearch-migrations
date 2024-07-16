package org.opensearch.migrations.transformation;

import org.opensearch.migrations.transformation.entity.Entity;

/**
 * Describes how to an entity is transformed from one version to another.
 */
public interface TransformationRule<T extends Entity> {
    /**
     * Given an entity can a transformation be run on it
     *
     * MUST ALWAYS BE READ ONLY
     */
    CanApplyResult canApply(T entity);

    /**
     * Apply a transformation on the entity
     * @param entity The entity to be transformed in place
     * @return true if the entity was updated, or false if no changes were made
     */
    boolean applyTransformation(T entity);
}
