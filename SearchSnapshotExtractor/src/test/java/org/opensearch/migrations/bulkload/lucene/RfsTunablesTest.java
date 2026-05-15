package org.opensearch.migrations.bulkload.lucene;

import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RfsTunablesTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(RfsTunables.SORT_BUFFER_BYTES_PROP);
    }

    // ---- constant wiring ----

    @Test
    void sortBufferBytesPropHasExpectedName() {
        assertEquals("rfs.reconstruction.sortBufferBytes", RfsTunables.SORT_BUFFER_BYTES_PROP);
    }

    @Test
    void defaultSortBufferBytesIs256MiB() {
        assertEquals(256L * 1024 * 1024, RfsTunables.DEFAULT_SORT_BUFFER_BYTES);
    }

    // ---- sortBufferBytes() behaviour ----

    @Test
    void sortBufferBytes_defaultWhenPropertyNotSet() {
        // No system property set -- should return the 256 MiB default.
        assertEquals(RfsTunables.DEFAULT_SORT_BUFFER_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_returnsOverrideWhenPropertySet() {
        long customValue = 512L * 1024 * 1024; // 512 MiB
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, String.valueOf(customValue));

        assertEquals(customValue, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_clampsSmallValueToRecordBytes() {
        // A value smaller than RECORD_BYTES should be clamped up.
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "1");

        assertEquals(SidecarBuilder.RECORD_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_returnsExactlyRecordBytesWhenSetToRecordBytes() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP,
                String.valueOf(SidecarBuilder.RECORD_BYTES));

        assertEquals(SidecarBuilder.RECORD_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_returnsDefaultForInvalidNonNumericValue() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "not-a-number");

        assertEquals(RfsTunables.DEFAULT_SORT_BUFFER_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_returnsDefaultForBlankValue() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "   ");

        assertEquals(RfsTunables.DEFAULT_SORT_BUFFER_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_returnsDefaultForEmptyValue() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "");

        assertEquals(RfsTunables.DEFAULT_SORT_BUFFER_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_handlesValueWithWhitespace() {
        long expected = 1024L * 1024;
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "  " + expected + "  ");

        assertEquals(expected, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_clampsNegativeValueToRecordBytes() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "-100");

        assertEquals(SidecarBuilder.RECORD_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }

    @Test
    void sortBufferBytes_clampsZeroToRecordBytes() {
        System.setProperty(RfsTunables.SORT_BUFFER_BYTES_PROP, "0");

        assertEquals(SidecarBuilder.RECORD_BYTES, SourcelessSpillConfig.sortBufferBytes());
    }
}
