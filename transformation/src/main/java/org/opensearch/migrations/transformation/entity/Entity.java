package org.opensearch.migrations.transformation.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Common interface for entities that are used to perform transformations
 *
 * Notes on performance: ObjectNode requires a fully marshalled json, for
 * less than 1mb json bodies this will likely be fine; however, if dealing with larger
 * objects or perf issues are encountered should consider adding an alternative
 * path for JsonParser.  This might be needed for processing document bodies
 * specifically.
 */
public interface Entity {
    /**
     * Gets the underlying entity as an ObjectNode, supports read and write operations
     */
    ObjectNode rawJson();
}
