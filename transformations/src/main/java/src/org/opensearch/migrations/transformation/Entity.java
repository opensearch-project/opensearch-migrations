package org.opensearch.migrations.transformation;

/**
 * Entitiesthat are used to perform transformations
 */
public interface Entity {
    default boolean isImmutable() {
        return true;
    }

    Version getSourceVersion();
}
