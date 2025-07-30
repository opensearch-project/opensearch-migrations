package org.opensearch.migrations.commands;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AllArgsConstructor
@ToString
public class ConfigureResult implements Result {
    @Getter
    private final int exitCode;

    @Getter
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
        
        try {
            return new ObjectMapper().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            Logger logger = LoggerFactory.getLogger(ConfigureResult.class);
            logger.error("Error converting ConfigureResult to JSON", e);
            return "{ \"error\": \"Failed to convert result to JSON\" }";
        }
    }
}
