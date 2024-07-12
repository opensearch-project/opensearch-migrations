package org.opensearch.migrations.trafficcapture;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class CodedOutputStreamSizeUtilTest {

    @Test
    void testGetSizeOfTimestamp() {
        // Timestamp with only seconds (no explicit nanoseconds)
        Instant timestampSecondsOnly = Instant.parse("2024-01-01T00:00:00Z");
        int sizeSecondsOnly = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestampSecondsOnly);
        Assertions.assertEquals(6, sizeSecondsOnly);

        // Timestamp with both seconds and nanoseconds
        Instant timestampWithNanos = Instant.parse("2024-12-31T23:59:59.123456789Z");
        int sizeWithNanos = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestampWithNanos);
        Assertions.assertEquals(11, sizeWithNanos);
    }

    @Test
    void testMaxBytesNeededForASegmentedObservation() {
        Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
        ByteBuf buf = Unpooled.buffer(100);
        buf.writeCharSequence("Test", StandardCharsets.UTF_8);
        int result = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(timestamp, 1, 2, buf);
        Assertions.assertEquals(24, result);
    }

    @Test
    void test_computeByteBufRemainingSize_emptyBufWithCapacity() {
        var buf = Unpooled.directBuffer(100);
        int result = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(2, buf);
        Assertions.assertEquals(2, result);
    }

    @Test
    void test_computeByteBufRemainingSize() {
        var buf = Unpooled.directBuffer();
        buf.writeCharSequence("hello_test", StandardCharsets.UTF_8);
        int result = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(2, buf);
        Assertions.assertEquals(12, result);
    }

    @Test
    void test_computeByteBufRemainingSize_largeBuf() {
        var buf = Unpooled.directBuffer();
        buf.writeCharSequence("1234567890".repeat(100000), StandardCharsets.UTF_8);
        int result = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(2, buf);
        Assertions.assertEquals(1000004, result);
    }

    @Test
    void test_computeByteBufRemainingSize_ByteBufAtCapacity() {
        ByteBuf buf = Unpooled.buffer(4);
        buf.writeCharSequence("Test", StandardCharsets.UTF_8);
        int result = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(2, buf);
        Assertions.assertEquals(6, result);
    }

    @Test
    void test_computeByteBufRemainingSize_EmptyByteBuf() {
        ByteBuf buf = Unpooled.buffer(0, 0);
        int result = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(2, buf);
        Assertions.assertEquals(2, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex() {
        int observationContentSize = 50;
        int numberOfTrafficStreamsSoFar = 10;

        int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(
            observationContentSize,
            numberOfTrafficStreamsSoFar
        );
        Assertions.assertEquals(54, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex_WithZeroContent() {
        int observationContentSize = 0;
        int numberOfTrafficStreamsSoFar = 0;

        int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(
            observationContentSize,
            numberOfTrafficStreamsSoFar
        );
        Assertions.assertEquals(4, result);
    }

    @Test
    void testBytesNeededForObservationAndClosingIndex_VariousIndices() {
        int observationContentSize = 20;

        // Test with increasing indices to verify scaling of index size
        int[] indices = new int[] { 1, 1000, 100000 };
        int[] expectedResults = new int[] { 24, 25, 26 };

        for (int i = 0; i < indices.length; i++) {
            int result = CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(
                observationContentSize,
                indices[i]
            );
            Assertions.assertEquals(expectedResults[i], result);
        }
    }

}
