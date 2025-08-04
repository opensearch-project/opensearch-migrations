package org.opensearch.migrations.commands;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for classes that can output their state as JSON.
 */
public interface JsonOutput {

    JsonNode asJsonOutput();
}
