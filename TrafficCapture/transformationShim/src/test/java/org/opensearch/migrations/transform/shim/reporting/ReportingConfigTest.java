package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ReportingConfigTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("reporting-config.yaml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parsesFullConfig() throws IOException {
        var config = ReportingConfig.parse(writeConfig("""
            enabled: true
            include_request_body: true
            sink:
              type: opensearch
              opensearch:
                uri: http://localhost:9200
                index_prefix: test-metrics
                bulk_size: 50
                flush_interval_ms: 3000
                auth:
                  username: admin
                  password: secret
                  tls:
                    insecure: true
            """));

        assertTrue(config.isEnabled());
        assertTrue(config.isIncludeRequestBody());
        assertTrue(config.hasSink());
        assertEquals("http://localhost:9200", config.getUri());
        assertEquals("test-metrics", config.getIndexPrefix());
        assertEquals(50, config.getBulkSize());
        assertEquals(3000, config.getFlushIntervalMs());
        assertEquals("admin", config.getUsername());
        assertEquals("secret", config.getPassword());
        assertTrue(config.isInsecureTls());
    }

    @Test
    void parsesMinimalConfig() throws IOException {
        var config = ReportingConfig.parse(writeConfig("""
            enabled: true
            sink:
              opensearch:
                uri: http://reporting:9200
            """));

        assertTrue(config.isEnabled());
        assertFalse(config.isIncludeRequestBody());
        assertTrue(config.hasSink());
        assertEquals("http://reporting:9200", config.getUri());
        assertEquals("shim-metrics", config.getIndexPrefix());
        assertEquals(100, config.getBulkSize());
        assertEquals(5000, config.getFlushIntervalMs());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertFalse(config.isInsecureTls());
    }

    @Test
    void disabledConfig() throws IOException {
        var config = ReportingConfig.parse(writeConfig("""
            enabled: false
            """));

        assertFalse(config.isEnabled());
        assertFalse(config.hasSink());
    }

    @Test
    void noSinkConfig() throws IOException {
        var config = ReportingConfig.parse(writeConfig("""
            enabled: true
            """));

        assertTrue(config.isEnabled());
        assertFalse(config.hasSink());
    }

    @Test
    void ignoresUnknownFields() throws IOException {
        var config = ReportingConfig.parse(writeConfig("""
            enabled: true
            unknown_field: value
            sink:
              type: opensearch
              opensearch:
                uri: http://localhost:9200
                extra_field: ignored
            """));

        assertTrue(config.hasSink());
        assertEquals("http://localhost:9200", config.getUri());
    }
}
