package org.opensearch.migrations.transformation.entity;

import org.opensearch.migrations.transformation.Version;

/**
 * Entities that are used to perform transformations
 */
public interface Entity {
    default boolean isImmutable() {
        return true;
    }

    Version getSourceVersion();
}
