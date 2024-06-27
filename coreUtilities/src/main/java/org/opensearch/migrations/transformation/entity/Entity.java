package org.opensearch.migrations.transformation.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Entities that are used to perform transformations
 */
public interface Entity {
    /**
     * Gets the underlying entity as an ObjectNode
     */
    ObjectNode raw();
}
