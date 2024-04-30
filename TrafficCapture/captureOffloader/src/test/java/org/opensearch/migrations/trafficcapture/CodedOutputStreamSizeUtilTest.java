package org.opensearch.migrations.trafficcapture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.time.Instant;

class CodedOutputStreamSizeUtilTest {

    @Test
    void testGetSizeOfTimestamp() {
        // Timestamp with only seconds (no explicit nanoseconds)
        Instant timestampSecondsOnly = Instant.parse("2024-01-01T00:00:00Z");
        int sizeSecondsOnly = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestampSecondsOnly);
        assertEquals( 6, sizeSecondsOnly);

        // Timestamp with both seconds and nanoseconds
        Instant timestampWithNanos = Instant.parse("2024-12-31T23:59:59.123456789Z");
        int sizeWithNanos = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestampWithNanos);
        assertEquals( 11, sizeWithNanos);
    }

    @Test
    void testMaxBytesNeededForASegmentedObservation() {
        Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
        ByteBuffer buffer = ByteBuffer.allocate(100).limit(50);
        buffer.position(25);
        int result = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(timestamp, 1, 2, buffer);
        assertEquals(45, result);
    }

    @Test
    void test_computeByteBufferRemainingSize() {
        ByteBuffer buffer = ByteBuffer.allocate(100).limit(50);
        int result = CodedOutputStreamSizeUtil.computeByteBufferRemainingSize(2, buffer);
        assertEquals(52, result);
    }

    @Test
    void test_computeByteBufferRemainingSize_ByteBufferAtCapacity() {
        ByteBuffer buffer = ByteBuffer.allocate(200);
        int result = CodedOutputStreamSizeUtil.computeByteBufferRemainingSize(2, buffer);
        assertEquals(203, result);
    }

    @Test
    void test_computeByteBufferRemainingSize_EmptyByteBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(0);
        int result = CodedOutputStreamSizeUtil.computeByteBufferRemainingSize(2, buffer);
        assertEquals(2, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex() {
        int observationContentSize = 50;
        int numberOfTrafficStreamsSoFar = 10;

        int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(observationContentSize, numberOfTrafficStreamsSoFar);
        assertEquals(54, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex_WithZeroContent() {
        int observationContentSize = 0;
        int numberOfTrafficStreamsSoFar = 0;

        int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(observationContentSize, numberOfTrafficStreamsSoFar);
        assertEquals(4, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex_VariousIndices() {
        int observationContentSize = 20;

        // Test with increasing indices to verify scaling of index size
        int[] indices = new int[]{1, 1000, 100000};
        int[] expectedResults = new int[]{24, 25, 26};

        for (int i = 0; i < indices.length; i++) {
            int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(observationContentSize, indices[i]);
            assertEquals(expectedResults[i], result);
        }
    }

}
