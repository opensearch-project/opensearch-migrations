package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AllowlistEntryTest {
    
    @Test
    void testLiteralEntry_exactMatch() {
        var entry = new AllowlistEntry("my-index");
        
        assertThat(entry.getOriginalValue(), equalTo("my-index"));
        assertThat(entry.matches("my-index"), equalTo(true));
        assertThat(entry.matches("my-index-2"), equalTo(false));
        assertThat(entry.matches("other-index"), equalTo(false));
    }
    
    @Test
    void testLiteralEntry_withDots() {
        var entry = new AllowlistEntry("my.index.2024");
        
        assertThat(entry.matches("my.index.2024"), equalTo(true));
        assertThat(entry.matches("myXindexX2024"), equalTo(false)); // Dots are literal, not regex wildcards
        assertThat(entry.matches("my.index.2025"), equalTo(false));
    }
    
    @Test
    void testLiteralEntry_withHyphens() {
        var entry = new AllowlistEntry("logs-app-2024");
        
        assertThat(entry.matches("logs-app-2024"), equalTo(true));
        assertThat(entry.matches("logs-app-2025"), equalTo(false));
    }
    
    @Test
    void testLiteralEntry_withSpecialChars() {
        var entry = new AllowlistEntry("index_name-v1.0");
        
        assertThat(entry.matches("index_name-v1.0"), equalTo(true));
        assertThat(entry.matches("index_name-v1X0"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_simple() {
        var entry = new AllowlistEntry("regex:logs-.*");
        
        assertThat(entry.getOriginalValue(), equalTo("regex:logs-.*"));
        assertThat(entry.matches("logs-app"), equalTo(true));
        assertThat(entry.matches("logs-web"), equalTo(true));
        assertThat(entry.matches("logs-"), equalTo(true));
        assertThat(entry.matches("metrics-app"), equalTo(false));
        assertThat(entry.matches("logs"), equalTo(false)); // Doesn't match because .* requires the hyphen
    }
    
    @Test
    void testRegexEntry_withDigits() {
        var entry = new AllowlistEntry("regex:test-\\d+");
        
        assertThat(entry.matches("test-1"), equalTo(true));
        assertThat(entry.matches("test-123"), equalTo(true));
        assertThat(entry.matches("test-0"), equalTo(true));
        assertThat(entry.matches("test-"), equalTo(false));
        assertThat(entry.matches("test-abc"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_complex() {
        var entry = new AllowlistEntry("regex:logs-[a-z]+-\\d{4}");
        
        assertThat(entry.matches("logs-app-2024"), equalTo(true));
        assertThat(entry.matches("logs-web-2024"), equalTo(true));
        assertThat(entry.matches("logs-app-24"), equalTo(false)); // Only 2 digits
        assertThat(entry.matches("logs-APP-2024"), equalTo(false)); // Uppercase not allowed
        assertThat(entry.matches("logs-app-"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_alternation() {
        var entry = new AllowlistEntry("regex:(logs|metrics)-.*-2024");
        
        assertThat(entry.matches("logs-app-2024"), equalTo(true));
        assertThat(entry.matches("metrics-cpu-2024"), equalTo(true));
        assertThat(entry.matches("traces-app-2024"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_dotMatching() {
        var entry = new AllowlistEntry("regex:logs\\..*");
        
        assertThat(entry.matches("logs.app"), equalTo(true));
        assertThat(entry.matches("logs.web.2024"), equalTo(true));
        assertThat(entry.matches("logsXapp"), equalTo(false)); // Dot is escaped, so only literal dot matches
    }
    
    @Test
    void testRegexEntry_fullMatch() {
        // Regex must match the entire string, not just a substring
        var entry = new AllowlistEntry("regex:test");
        
        assertThat(entry.matches("test"), equalTo(true));
        assertThat(entry.matches("test123"), equalTo(false)); // Doesn't match because regex must match entire string
        assertThat(entry.matches("mytest"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_emptyPattern() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new AllowlistEntry("regex:");
        });
        assertThat(exception.getMessage().contains("empty"), equalTo(true));
    }
    
    @Test
    void testRegexEntry_invalidPattern() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new AllowlistEntry("regex:[invalid");
        });
        assertThat(exception.getMessage().contains("Invalid regex pattern"), equalTo(true));
        assertThat(exception.getMessage().contains("regex:[invalid"), equalTo(true));
    }
    
    @Test
    void testRegexEntry_invalidPattern_unclosedGroup() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new AllowlistEntry("regex:(unclosed");
        });
        assertThat(exception.getMessage().contains("Invalid regex pattern"), equalTo(true));
    }
    
    @Test
    void testNullValue() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new AllowlistEntry(null);
        });
        assertThat(exception.getMessage().contains("cannot be null"), equalTo(true));
    }
    
    @Test
    void testMatches_nullTestValue() {
        var literalEntry = new AllowlistEntry("test");
        var regexEntry = new AllowlistEntry("regex:test.*");
        
        assertThat(literalEntry.matches(null), equalTo(false));
        assertThat(regexEntry.matches(null), equalTo(false));
    }
    
    @Test
    void testEmptyString() {
        var entry = new AllowlistEntry("");
        
        assertThat(entry.matches(""), equalTo(true));
        assertThat(entry.matches("anything"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_matchesEmptyString() {
        var entry = new AllowlistEntry("regex:.*");
        
        assertThat(entry.matches(""), equalTo(true));
        assertThat(entry.matches("anything"), equalTo(true));
    }
    
    @Test
    void testLiteralEntry_startsWithRegexLikePattern() {
        // A literal entry that looks like it could be regex but isn't
        var entry = new AllowlistEntry("test.*");
        
        assertThat(entry.matches("test.*"), equalTo(true));
        assertThat(entry.matches("test123"), equalTo(false)); // Not treated as regex
    }
    
    @Test
    void testRegexEntry_caseInsensitive() {
        // By default, regex is case-sensitive
        var entry = new AllowlistEntry("regex:test");
        
        assertThat(entry.matches("test"), equalTo(true));
        assertThat(entry.matches("TEST"), equalTo(false));
        assertThat(entry.matches("Test"), equalTo(false));
    }
    
    @Test
    void testRegexEntry_withEscapedSpecialChars() {
        var entry = new AllowlistEntry("regex:test-\\[\\d+\\]");
        
        assertThat(entry.matches("test-[123]"), equalTo(true));
        assertThat(entry.matches("test-[0]"), equalTo(true));
        assertThat(entry.matches("test-123"), equalTo(false)); // Brackets are required
    }
}
