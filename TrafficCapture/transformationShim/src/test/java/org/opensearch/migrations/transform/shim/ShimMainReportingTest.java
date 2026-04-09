package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ShimMainReportingTest {

    @TempDir
    Path tempDir;

    @Test
    void initReportingReturnsNullWhenPathIsNull() {
        Object[] result = ShimMain.initReporting(null);
        assertNull(result[0]);
        assertNull(result[1]);
    }

    @Test
    void initReportingReturnsNullForDisabledConfig() throws IOException {
        Path config = tempDir.resolve("reporting.json");
        Files.writeString(config, "{\"enabled\": false}");

        Object[] result = ShimMain.initReporting(config.toString());
        assertNull(result[0]);
        assertNull(result[1]);
    }

    @Test
    void initReportingReturnsNullForMissingSink() throws IOException {
        Path config = tempDir.resolve("reporting.json");
        Files.writeString(config, "{\"enabled\": true}");

        Object[] result = ShimMain.initReporting(config.toString());
        assertNull(result[0]);
        assertNull(result[1]);
    }

    @Test
    void initReportingReturnsReceiversForValidConfig() throws IOException {
        Path config = tempDir.resolve("reporting.json");
        Files.writeString(config, """
            {
              "enabled": true,
              "include_request_body": false,
              "sink": {
                "opensearch": {
                  "uri": "http://localhost:1",
                  "index_prefix": "test",
                  "bulk_size": 10,
                  "flush_interval_ms": 60000
                }
              }
            }
            """);

        Object[] result = ShimMain.initReporting(config.toString());
        assertNotNull(result[0], "MetricsReceiver should not be null");
        assertNotNull(result[1], "OpenSearchMetricsSink should not be null");

        // Clean up the sink's scheduler
        if (result[1] instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }

    @Test
    void initReportingHandlesInvalidPath() {
        Object[] result = ShimMain.initReporting("/nonexistent/path/config.json");
        assertNull(result[0]);
        assertNull(result[1]);
    }
}
