package org.opensearch.migrations.commands;

import org.opensearch.migrations.utils.JsonUtils;

/**
 * Interface for classes that can output their state as JSON.
 */
public interface JsonOutput {
    /**
     * Return a JSON representation of this object.
     */
    String asJsonOutput();
    
    /**
     * Default implementation that can be used by simple classes that can be 
     * directly serialized to JSON by Jackson.
     */
    default String toJsonString() {
        return JsonUtils.toJson(this, this.getClass().getSimpleName());
    }
}
