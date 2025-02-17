package org.opensearch.migrations.dashboards;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SanitizerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testSanitize() throws IOException, URISyntaxException {
        // Given a json data with no 'allowNoIndex' attribute
        final String jsonString = readResourceFile("/data/index-pattern/input-003-version-7.6.0.json");
        final Sanitizer sanitizer = Sanitizer.getInstance();
        
        // When calling sanitize
        final var sanitized = objectMapper.readTree(sanitizer.sanitize(jsonString));
        
        // Then the sanitized string should be the same as the input
        final var expected = objectMapper.readTree(jsonString);
        assertEquals(expected, sanitized);
    }

    @Test
    public void testDeterministicBehavior() throws IOException, URISyntaxException {
        // Given a json data with no 'allowNoIndex' attribute
        final String jsonString = readResourceFile("/data/index-pattern/input-003-version-7.6.0.json");
        final Sanitizer sanitizer = Sanitizer.getInstance();
        
        // When calling sanitize
        final String sanitizedString = sanitizer.sanitize(jsonString);
        final String sanitizedString2 = sanitizer.sanitize(jsonString);
        
        // Then the sanitized string should be the same as the input string - but it needs to be a one line string
        assertEquals(sanitizedString, sanitizedString2);
    }

    @Test
    public void testSupportForNewObjectCreation() throws IOException, URISyntaxException {
        // Given a json data with no 'allowNoIndex' attribute
        final String jsonString = readResourceFile("/data/dashboard/input-001-version-8.8.0.json");
        final Sanitizer sanitizer = Sanitizer.getInstance();
        
        // When calling sanitize
        final String sanitizedString = sanitizer.sanitize(jsonString);
        
        // Then the sanitized string should be the same as the input string - but it needs to be a one line string
        assertEquals(3, sanitizedString.split(System.lineSeparator()).length);
    }

    private String readResourceFile(String fileName) throws IOException, URISyntaxException {
        return new String(Files.readAllBytes(Path.of(getClass().getResource(fileName).toURI())));
    }
}
