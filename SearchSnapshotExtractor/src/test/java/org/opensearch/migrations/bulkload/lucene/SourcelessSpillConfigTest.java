package org.opensearch.migrations.bulkload.lucene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the system-property contract for {@link SourcelessSpillConfig} — defaults,
 * parse-failure fallback, and clamp behavior. Operators rely on these props to
 * tune the sourceless-reconstruction spill budget without a code change, so
 * silent regressions here would re-open issue #2961's failure mode.
 */
class SourcelessSpillConfigTest {

    private String savedSort;
    private String savedMax;
    private String savedFloor;

    @BeforeEach
    void saveProps() {
        savedSort  = System.getProperty(SourcelessSpillConfig.SORT_BUFFER_BYTES_PROP);
        savedMax   = System.getProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP);
        savedFloor = System.getProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP);
        System.clearProperty(SourcelessSpillConfig.SORT_BUFFER_BYTES_PROP);
        System.clearProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP);
        System.clearProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP);
    }

    @AfterEach
    void restoreProps() {
        restore(SourcelessSpillConfig.SORT_BUFFER_BYTES_PROP, savedSort);
        restore(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP, savedMax);
        restore(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP, savedFloor);
    }

    private static void restore(String prop, String value) {
        if (value == null) System.clearProperty(prop);
        else System.setProperty(prop, value);
    }

    @Test
    void defaults_whenUnset() {
        assertEquals(SourcelessSpillConfig.DEFAULT_SORT_BUFFER_BYTES, SourcelessSpillConfig.sortBufferBytes());
        assertEquals(SourcelessSpillConfig.DEFAULT_MAX_SPILL_BYTES, SourcelessSpillConfig.maxSpillBytes());
        assertEquals(SourcelessSpillConfig.DEFAULT_SPILL_FREE_SPACE_MIN_BYTES, SourcelessSpillConfig.spillFreeSpaceMinBytes());
    }

    @Test
    void sortBufferBytes_clampsBelowRecordWidth() {
        System.setProperty(SourcelessSpillConfig.SORT_BUFFER_BYTES_PROP, "1");
        assertEquals(SidecarBuilder.RECORD_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void parseFailure_fallsBackToDefault() {
        System.setProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP, "not-a-number");
        assertEquals(SourcelessSpillConfig.DEFAULT_MAX_SPILL_BYTES, SourcelessSpillConfig.maxSpillBytes());
    }

    @Test
    void maxSpillBytes_negativeClampsToZero() {
        System.setProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP, "-1");
        assertEquals(0L, SourcelessSpillConfig.maxSpillBytes());
    }

    @Test
    void spillFreeSpaceMinBytes_acceptsValidValue() {
        System.setProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP, "1073741824");
        assertEquals(1073741824L, SourcelessSpillConfig.spillFreeSpaceMinBytes());
    }
}
