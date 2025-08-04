package org.opensearch.migrations.commands;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class ConfigureResult implements Result {
    @Getter
    @JsonProperty
    private final int exitCode;

    @Getter
    @JsonProperty
    private final String errorMessage;

    public String asCliOutput() {
        return this.toString();
    }
    
    @Override
    public JsonNode asJsonOutput() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("exitCode", exitCode);

        if (errorMessage != null && !errorMessage.isEmpty()) {
            root.put("errorMessage", errorMessage);
        }

        return root;
    }
}
