package com.rfs.common.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import lombok.SneakyThrows;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SigV4AuthTransformerTest {

    private SigV4AuthTransformer transformer;
    private AwsCredentialsProvider credentialsProvider;

    @BeforeEach
    void setUp() {
        credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        );
        Clock fixedClock = Clock.fixed(Instant.parse("2023-04-20T12:00:00Z"), ZoneOffset.UTC);
        transformer = new SigV4AuthTransformer(credentialsProvider, "es", "us-east-1", "https", () -> fixedClock);
    }

    @Test
    void testTransform() {
        String method = "GET";
        String path = "/";
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Host", List.of("example.amazonaws.com"));
        Mono<ByteBuffer> body = Mono.empty();

        Mono<TransformedRequest> transformedRequestMono = transformer.transform(method, path, headers, body);

        TransformedRequest transformedRequest = transformedRequestMono.block();

        assertNotNull(transformedRequest);
        Map<String, List<String>> transformedHeaders = transformedRequest.getHeaders();

        assertTrue(transformedHeaders.containsKey("Authorization"));
        String authHeader = transformedHeaders.get("Authorization").get(0);
        assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20230420/us-east-1/es/aws4_request"));

        assertEquals(List.of("20230420T120000Z"), transformedHeaders.get("X-Amz-Date"));
        assertNull(transformedHeaders.get("x-amz-content-sha256"));
        assertNull(transformedRequest.getBody().block());
    }

    void testTransformWithPayload(String payloadString) {
        String method = "POST";
        String path = "/index/_doc";
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Host", List.of("example.amazonaws.com"));
        headers.put("Content-Type", List.of("application/json"));
        var payload = ByteBuffer.wrap(payloadString.getBytes(StandardCharsets.UTF_8));
        var body = Mono.just(payload);

        var transformedRequest = transformer.transform(method, path, headers, body).block();

        // Verify input/output body was not modified (including positional arguments)
        assertEquals(payloadString, byteBufferToString(payload));
        assertEquals(payloadString, byteBufferToString(transformedRequest.getBody().block()));

        assertNotNull(transformedRequest);
        Map<String, List<String>> transformedHeaders = transformedRequest.getHeaders();

        assertTrue(transformedHeaders.containsKey("Authorization"));
        String authHeader = transformedHeaders.get("Authorization").get(0);
        assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20230420/us-east-1/es/aws4_request"));

        assertEquals(List.of("20230420T120000Z"), transformedHeaders.get("X-Amz-Date"));
        assertNotNull(transformedHeaders.get("x-amz-content-sha256"));
        assertEquals(List.of(calculateSHA256(payloadString)),
            transformedHeaders.get("x-amz-content-sha256"));

        // Verify that the body is unchanged
        assertEquals(payload, transformedRequest.getBody().block());
    }

    @Test
    void testTransformWithPayloadMultipleTimes() {
        testTransformWithPayload("payloadString");
        testTransformWithPayload("payloadString2");
        testTransformWithPayload("payloadString3");
    }

    public static String byteBufferToString(ByteBuffer byteBuffer) {
        // Create a byte array from the ByteBuffer
        var duplicateBuffer = byteBuffer.duplicate();
        byte[] bytes = new byte[duplicateBuffer.remaining()];
        duplicateBuffer.get(bytes);

        // Convert byte array to String
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    private static String calculateSHA256(String payload) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encodedHash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(encodedHash);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
