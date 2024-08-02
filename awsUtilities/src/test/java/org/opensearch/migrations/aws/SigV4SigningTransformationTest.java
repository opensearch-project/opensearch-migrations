package org.opensearch.migrations.aws;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.IHttpMessage;

import lombok.SneakyThrows;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SigV4SigningTransformationTest {

    private static class MockCredentialsProvider implements AwsCredentialsProvider {
        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        }
    }

    private static Stream<Arguments> testCases() {
        return Stream.of(
            Arguments.of("GET", "/", null),
            Arguments.of("PUT", "/index/_doc/1", "{\"field\":\"value\"}"),
            Arguments.of("DELETE", "/index/_doc/1", null),
            Arguments.of("HEAD", "/", null)
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void testSignatureProperlyApplied(String method, String path, String payload) {
        // Setup
        var mockCredentialsProvider = new MockCredentialsProvider();
        var signer = new SigV4Signer(
            mockCredentialsProvider,
            "es",
            "us-east-1",
            "https",
            () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );

        // Create a mock HTTP message
        var httpMessage = modifiableHttpMessage(method, path, "HTTP/1.1", new HashMap<>());
        if (payload != null) {
            httpMessage.headers().put("Content-Type", List.of("application/json"));
        }
        // Set the host header
        httpMessage.headers().put("Host", List.of("example.amazonaws.com"));

        // Apply the body
        if (payload != null) {
            signer.consumeNextPayloadPart(StandardCharsets.UTF_8.encode(payload));
        }

        var signedHeaders = signer.finalizeSignature(httpMessage);

        // Verify the results
        assertNotNull(signedHeaders);
        assertTrue(signedHeaders.containsKey("Authorization"));
        String authHeader = signedHeaders.get("Authorization").get(0);
        assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request"),
            "Expected 'Authorization' header to be present in " + authHeader + " but got '" + authHeader + "'");
        assertEquals(List.of("19700101T000000Z"), signedHeaders.get("X-Amz-Date"));

        Optional<String> expectedHash = Optional.ofNullable(payload)
                .map(SigV4SigningTransformationTest::calculateSHA256);

        assertEquals(expectedHash.map(List::of).orElse(null), signedHeaders.get("x-amz-content-sha256"));

        // Verify header map returned is unmodifiable (check both keys and values)
        assertThrows(UnsupportedOperationException.class, () -> signedHeaders.put("Test", List.of("Value")));
        assertThrows(UnsupportedOperationException.class, () -> signedHeaders.get("Authorization").add("Test"));
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

    private static IHttpMessage modifiableHttpMessage(
        final String method,
        final String path,
        final String protocol,
        final Map<String, List<String>> headers
    ) {
        return new IHttpMessage() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public String protocol() {
                return protocol;
            }

            @Override
            public Map<String, List<String>> headers() {
                return headers;
            }
        };
    }
}
