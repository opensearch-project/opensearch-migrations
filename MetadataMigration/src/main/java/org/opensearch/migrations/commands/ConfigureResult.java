package org.opensearch.migrations.commands;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.migrations.utils.JsonUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    public String asJsonOutput() {
        Map<String, Object> json = new HashMap<>();
        json.put("exitCode", exitCode);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            json.put("errorMessage", errorMessage);
        }
        
        return JsonUtils.toJson(json, "ConfigureResult");
    }
}
