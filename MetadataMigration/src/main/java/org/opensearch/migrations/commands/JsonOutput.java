package org.opensearch.migrations.commands;

/**
 * Interface for classes that can output their state as JSON.
 */
public interface JsonOutput {
    /**
     * Return a JSON representation of this object.
     */
    String asJsonOutput();
}
