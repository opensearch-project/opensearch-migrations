package com.rfs.common.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipPayloadRequestTransformerTest {

    private static final int RANDOM_SEED = 42; // Fixed seed for reproducibility
    private GzipPayloadRequestTransformer gzipTransformer;

    @BeforeEach
    public void setup() {
        gzipTransformer = new GzipPayloadRequestTransformer();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 100, 1024, 10 * 1024, 1024 * 1024 }) // 0B, 100B, 1KB, 10KB, 1MB
    void testGzipCompression(int size) throws Exception {
        // Generate test data
        ByteBuffer inputBuffer = generateTestData(size);
        Map<String, List<String>> headers = new HashMap<>();

        // Store initial position and limit
        int initialPosition = inputBuffer.position();
        int initialLimit = inputBuffer.limit();

        // Compress
        TransformedRequest result = gzipTransformer.transform("POST", "/test", headers, Mono.just(inputBuffer)).block();

        // Check headers
        assertTrue(result.getHeaders().containsKey("content-encoding"));
        assertEquals("gzip", result.getHeaders().get("content-encoding").get(0));

        // Decompress and verify
        ByteBuffer compressedBuffer = result.getBody().block();
        byte[] decompressed = decompress(compressedBuffer);

        assertArrayEquals(inputBuffer.array(), decompressed);

        // Verify size decreased (except for very small inputs where gzip overhead might increase size)
        if (size > 100) {
            assertTrue(
                compressedBuffer.remaining() < inputBuffer.remaining(),
                "Compressed size should be smaller than input size for inputs larger than 100 bytes"
            );
        }

        // Verify that the input buffer wasn't read by the transformation
        assertEquals(initialPosition, inputBuffer.position(), "Input buffer position should not change");
        assertEquals(initialLimit, inputBuffer.limit(), "Input buffer limit should not change");
    }

    @ParameterizedTest
    @ValueSource(ints = { 100, 1024, 10 * 1024, 1024 * 1024 }) // 100B, 1KB, 10KB, 1MB
    void testGzipCompressionWithDirectBuffer(int size) throws Exception {
        // Generate test data in a direct ByteBuffer
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(size);
        Random random = new Random(RANDOM_SEED);
        for (int i = 0; i < size; i++) {
            inputBuffer.put((byte) random.nextInt(48));
        }
        inputBuffer.flip();

        Map<String, List<String>> headers = new HashMap<>();

        // Store initial position and limit
        int initialPosition = inputBuffer.position();
        int initialLimit = inputBuffer.limit();

        // Compress
        TransformedRequest result = gzipTransformer.transform("POST", "/test", headers, Mono.just(inputBuffer)).block();

        // Check headers
        assertTrue(result.getHeaders().containsKey("content-encoding"));
        assertEquals("gzip", result.getHeaders().get("content-encoding").get(0));

        // Decompress and verify
        ByteBuffer compressedBuffer = result.getBody().block();
        byte[] decompressed = decompress(compressedBuffer);

        // Create a byte array from the direct buffer for comparison
        byte[] inputArray = new byte[inputBuffer.limit()];
        inputBuffer.get(inputArray);
        inputBuffer.position(initialPosition); // Reset position after reading

        assertArrayEquals(inputArray, decompressed);

        // Verify size decreased (except for very small inputs where gzip overhead might increase size)
        if (size > 100) {
            assertTrue(
                compressedBuffer.remaining() < inputBuffer.remaining(),
                "Compressed size should be smaller than input size for inputs larger than 100 bytes"
            );
        }

        // Verify that the input buffer wasn't read by the transformation
        assertEquals(initialPosition, inputBuffer.position(), "Input buffer position should not change");
        assertEquals(initialLimit, inputBuffer.limit(), "Input buffer limit should not change");
    }

    @Test
    void testEmptyInput() {
        Map<String, List<String>> headers = new HashMap<>();

        TransformedRequest result = gzipTransformer.transform("GET", "/test", headers, Mono.empty()).block();

        assertFalse(result.getHeaders().containsKey("content-encoding"));
        assertNull(result.getBody().block());
    }

    @Test
    void testLargeInput() throws Exception {
        int largeSize = 50 * 1024 * 1024; // 50MB
        ByteBuffer largeBuffer = generateTestData(largeSize);
        Map<String, List<String>> headers = new HashMap<>();

        // Store initial position and limit
        int initialPosition = largeBuffer.position();
        int initialLimit = largeBuffer.limit();

        TransformedRequest result = gzipTransformer.transform("POST", "/test", headers, Mono.just(largeBuffer)).block();

        ByteBuffer compressedBuffer = result.getBody().block();
        byte[] decompressed = decompress(compressedBuffer);

        assertArrayEquals(largeBuffer.array(), decompressed);

        // Verify size decreased
        assertTrue(
            compressedBuffer.remaining() < largeBuffer.remaining(),
            "Compressed size should be smaller than input size for large inputs"
        );

        // Verify that the input buffer wasn't read by the transformation
        assertEquals(initialPosition, largeBuffer.position(), "Input buffer position should not change");
        assertEquals(initialLimit, largeBuffer.limit(), "Input buffer limit should not change");
    }

    @Test
    public void testLargeInputWithDirectBuffer() throws Exception {
        int largeSize = 50 * 1024 * 1024; // 50MB
        ByteBuffer largeBuffer = ByteBuffer.allocateDirect(largeSize);
        Random random = new Random(RANDOM_SEED);
        for (int i = 0; i < largeSize; i++) {
            largeBuffer.put((byte) random.nextInt(48));
        }
        largeBuffer.flip();

        Map<String, List<String>> headers = new HashMap<>();

        // Store initial position and limit
        int initialPosition = largeBuffer.position();
        int initialLimit = largeBuffer.limit();

        TransformedRequest result = gzipTransformer.transform("POST", "/test", headers, Mono.just(largeBuffer)).block();

        ByteBuffer compressedBuffer = result.getBody().block();
        byte[] decompressed = decompress(compressedBuffer);

        // Create a byte array from the direct buffer for comparison
        byte[] inputArray = new byte[largeBuffer.limit()];
        largeBuffer.get(inputArray);
        largeBuffer.position(initialPosition); // Reset position after reading

        assertArrayEquals(inputArray, decompressed);

        // Verify size decreased
        assertTrue(
            compressedBuffer.remaining() < largeBuffer.remaining(),
            "Compressed size should be smaller than input size for large inputs"
        );

        // Verify that the input buffer wasn't read by the transformation
        assertEquals(initialPosition, largeBuffer.position(), "Input buffer position should not change");
        assertEquals(initialLimit, largeBuffer.limit(), "Input buffer limit should not change");
    }

    private ByteBuffer generateTestData(int size) {
        Random random = new Random(RANDOM_SEED);
        ByteBuffer buffer = ByteBuffer.allocate(size);

        for (int i = 0; i < size; i++) {
            buffer.put((byte) random.nextInt(48));
        }

        buffer.flip();
        return buffer;
    }

    private byte[] decompress(ByteBuffer compressedBuffer) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedBuffer.array()))) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }

    @Test
    public void testAlreadyCompressedPayload() throws Exception {
        // Generate test data and compress it
        ByteBuffer originalBuffer = generateTestData(1024); // 1KB of test data
        byte[] compressedData = compress(originalBuffer);
        ByteBuffer compressedBuffer = ByteBuffer.wrap(compressedData);

        // Set headers to indicate that the content should be gzipped
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("content-encoding", List.of("gzip"));

        // Store initial position and limit
        int initialPosition = compressedBuffer.position();
        int initialLimit = compressedBuffer.limit();

        // Transform
        TransformedRequest result =
            gzipTransformer.transform("POST", "/test", headers, Mono.just(compressedBuffer)).block();

        // Check headers
        assertTrue(result.getHeaders().containsKey("content-encoding"));
        assertEquals("gzip", result.getHeaders().get("content-encoding").get(0));

        // Verify that the body is unchanged
        ByteBuffer resultBuffer = result.getBody().block();
        assertEquals(initialPosition, resultBuffer.position(), "Compressed buffer position should not change");
        assertEquals(initialLimit, resultBuffer.limit(), "Compressed buffer limit should not change");
        assertArrayEquals(compressedBuffer.array(), resultBuffer.array(), "Compressed payload should remain unchanged");
    }

    private byte[] compress(ByteBuffer inputBuffer) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(baos)) {
            gzipOutputStream.write(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
        }
        return baos.toByteArray();
    }
}
