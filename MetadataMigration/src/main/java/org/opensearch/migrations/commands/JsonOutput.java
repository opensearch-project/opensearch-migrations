package org.opensearch.migrations.commands;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for classes that can output their state as JSON.
 */
public interface JsonOutput {
    /**
     * Return a JSON representation of this object as a JsonNode.
     * Using JsonNode instead of String makes chaining operations more efficient 
     * and prevents unnecessary serialization/deserialization cycles.
     */
    JsonNode asJsonOutput();
}
