package org.opensearch.migrations.bulkload.common.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GzipPayloadRequestTransformerTest {

    private final GzipPayloadRequestTransformer transformer = new GzipPayloadRequestTransformer();

    @Test
    void uncompressedPayload_withGzipHeader_isCompressed() {
        var headers = new HashMap<String, List<String>>();
        headers.put(GzipPayloadRequestTransformer.CONTENT_ENCODING_HEADER_NAME,
            List.of(GzipPayloadRequestTransformer.GZIP_CONTENT_ENCODING_HEADER_VALUE));

        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        var body = Mono.just(ByteBuffer.wrap(payload));

        var result = transformer.transform("POST", "/test", headers, body).block();
        var compressed = result.getBody().block();

        // Verify gzip magic bytes (0x1F, 0x8B)
        byte[] bytes = new byte[compressed.remaining()];
        compressed.get(bytes);
        assertEquals((byte) 0x1F, bytes[0]);
        assertEquals((byte) 0x8B, bytes[1]);
    }

    @Test
    void payload_withoutGzipHeader_isNotCompressed() {
        var headers = new HashMap<String, List<String>>();
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        var body = Mono.just(ByteBuffer.wrap(payload));

        var result = transformer.transform("POST", "/test", headers, body).block();
        var output = result.getBody().block();

        byte[] bytes = new byte[output.remaining()];
        output.get(bytes);
        assertArrayEquals(payload, bytes);
    }

    @Test
    void alreadyCompressedPayload_isNotDoubleCompressed() {
        var headers = new HashMap<String, List<String>>();
        headers.put(GzipPayloadRequestTransformer.CONTENT_ENCODING_HEADER_NAME,
            List.of(GzipPayloadRequestTransformer.GZIP_CONTENT_ENCODING_HEADER_VALUE));

        // Gzip magic bytes followed by dummy data
        byte[] gzipPayload = new byte[] { 0x1F, (byte) 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00 };
        var body = Mono.just(ByteBuffer.wrap(gzipPayload));

        var result = transformer.transform("POST", "/test", headers, body).block();
        var output = result.getBody().block();

        byte[] bytes = new byte[output.remaining()];
        output.get(bytes);
        assertArrayEquals(gzipPayload, bytes);
    }

    @Test
    void emptyBody_withGzipHeader_passesThrough() {
        var headers = new HashMap<String, List<String>>();
        headers.put(GzipPayloadRequestTransformer.CONTENT_ENCODING_HEADER_NAME,
            List.of(GzipPayloadRequestTransformer.GZIP_CONTENT_ENCODING_HEADER_VALUE));

        var result = transformer.transform("GET", "/test", headers, Mono.empty()).block();
        assertFalse(result.getBody().hasElement().block());
    }

    @Test
    void headersArePreserved() {
        var headers = new HashMap<String, List<String>>();
        headers.put("X-Custom", List.of("value1"));

        var result = transformer.transform("GET", "/test", headers, Mono.empty()).block();
        assertEquals(List.of("value1"), result.getHeaders().get("X-Custom"));
    }
}
