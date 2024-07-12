package org.opensearch.migrations.tracing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommonScopedMetricInstrumentsTest {
    @Test
    public void testThatBadSizeThrowsException() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new CommonScopedMetricInstruments(null, "testActivity", 0, 2)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new CommonScopedMetricInstruments(null, "testActivity", -2, 2)
        );
        var otelSdkBundle = new InMemoryInstrumentationBundle(false, false);
        Assertions.assertDoesNotThrow(
            () -> new CommonScopedMetricInstruments(
                otelSdkBundle.getOpenTelemetrySdk().getMeter(""),
                "testActivity",
                1,
                8
            )
        );
    }
}
