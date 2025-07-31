package org.opensearch.migrations.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * A simple implementation of Result that demonstrates best practices for JSON serialization.
 * This class uses Jackson annotations and the default toJsonString() method from JsonOutput interface.
 */
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimpleResult implements Result {
    @Getter
    @JsonProperty("exitCode")
    private final int exitCode;

    @Getter
    @JsonProperty("errorMessage")
    private final String errorMessage;
    
    /**
     * Creates a successful result with no error message.
     */
    public static SimpleResult success() {
        return new SimpleResult(0, null);
    }
    
    /**
     * Creates an error result with the specified error message.
     */
    public static SimpleResult error(String message) {
        return new SimpleResult(1, message);
    }
    
    /**
     * Creates an error result with the specified exit code and error message.
     */
    public static SimpleResult error(int exitCode, String message) {
        return new SimpleResult(exitCode, message);
    }

    @Override
    public String asCliOutput() {
        if (exitCode == 0) {
            return "Success";
        } else {
            return "Error (code " + exitCode + "): " + errorMessage;
        }
    }
    
    @Override
    public String asJsonOutput() {
        // Use the default implementation from JsonOutput interface
        return toJsonString();
    }
}
