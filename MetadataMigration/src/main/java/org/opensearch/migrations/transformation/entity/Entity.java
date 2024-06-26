package org.opensearch.migrations.transformation.entity;

import org.opensearch.migrations.transformation.Version;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Entities that are used to perform transformations
 */
public interface Entity {
    default boolean isImmutable() {
        return true;
    }

    /** TODO: Circle back to update this  */
    default ObjectNode raw() {
        return null;
    }

    Version getSourceVersion();
}
