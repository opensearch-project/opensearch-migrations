package org.opensearch.migrations.bulkload.common;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents an allowlist entry that can be either a literal string match or a regex pattern.
 * 
 * Entries prefixed with "regex:" are treated as regular expression patterns.
 * All other entries are treated as exact literal string matches.
 * 
 * Examples:
 * - "my-index" matches only "my-index" (literal)
 * - "my.index.2024" matches only "my.index.2024" (literal, dots are not regex wildcards)
 * - "regex:logs-.*-2024" matches "logs-app-2024", "logs-web-2024", etc. (regex)
 * - "regex:test-\d+" matches "test-1", "test-123", etc. (regex)
 */
public class AllowlistEntry {
    private static final String REGEX_PREFIX = "regex:";
    
    private final String originalValue;
    private final Pattern pattern;
    
    /**
     * Creates an allowlist entry from a string value.
     * 
     * @param value The allowlist entry value. If it starts with "regex:", the remainder
     *              is compiled as a regular expression pattern. Otherwise, it's treated
     *              as a literal string for exact matching.
     * @throws IllegalArgumentException if the value starts with "regex:" but the pattern
     *                                  is invalid
     */
    public AllowlistEntry(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Allowlist entry value cannot be null");
        }
        
        this.originalValue = value;
        
        if (value.startsWith(REGEX_PREFIX)) {
            String patternString = value.substring(REGEX_PREFIX.length());
            
            if (patternString.isEmpty()) {
                throw new IllegalArgumentException("Regex pattern cannot be empty after 'regex:' prefix");
            }
            
            try {
                this.pattern = Pattern.compile(patternString);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                    "Invalid regex pattern in allowlist entry '" + value + "': " + e.getMessage(),
                    e
                );
            }
        } else {
            this.pattern = null;
        }
    }
    
    /**
     * Tests if the given string matches this allowlist entry.
     * 
     * For literal entries, this performs an exact string match.
     * For regex entries, this tests if the pattern matches the entire string.
     * 
     * @param testValue The string to test against this entry
     * @return true if the string matches this entry, false otherwise
     */
    public boolean matches(String testValue) {
        if (testValue == null) {
            return false;
        }
        
        if (pattern != null) {
            return pattern.matcher(testValue).matches();
        } else {
            return originalValue.equals(testValue);
        }
    }
    
    /**
     * Returns the original input value including any prefix.
     * 
     * @return The original value passed to the constructor
     */
    public String getOriginalValue() {
        return originalValue;
    }
}
