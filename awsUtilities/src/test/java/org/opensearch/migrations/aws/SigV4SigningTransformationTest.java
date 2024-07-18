package org.opensearch.migrations.aws;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.transform.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.transform.ListKeyAdaptingCaseInsensitiveHeadersMap;
import org.opensearch.migrations.transform.PayloadAccessFaultingMap;
import org.opensearch.migrations.transform.StrictCaseInsensitiveHttpHeadersMap;

import lombok.SneakyThrows;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import static org.opensearch.migrations.transform.JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SigV4SigningTransformationTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

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
    @SuppressWarnings("unchecked")
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
        var httpMessage = new HttpJsonMessageWithFaultingPayload();
        httpMessage.setMethod(method);
        httpMessage.setPath(path);

        StrictCaseInsensitiveHttpHeadersMap strictHeaders = new StrictCaseInsensitiveHttpHeadersMap();
        ListKeyAdaptingCaseInsensitiveHeadersMap headers = new ListKeyAdaptingCaseInsensitiveHeadersMap(strictHeaders);
        headers.put("host", List.of("localhost"));
        httpMessage.setHeaders(headers);

        var payloadAccessFaultingMap = new PayloadAccessFaultingMap(strictHeaders);
        if (payload != null && !payload.isEmpty()) {
            try {
                // Parse the payload string into a JSON object
                Object jsonPayload = objectMapper.readValue(payload, new TypeReference<Object>() {});
                payloadAccessFaultingMap.put(INLINED_JSON_BODY_DOCUMENT_KEY, jsonPayload);
            } catch (Exception e) {
                // Handle parsing exception
                throw new RuntimeException("Failed to parse payload as JSON", e);
            }
        }
        httpMessage.setPayloadFaultMap(payloadAccessFaultingMap);

        // Apply the signature
        if (payload != null) {
            signer.consumeNextPayloadPart(StandardCharsets.UTF_8.encode(payload));
        }
        signer.finalizeSignature(httpMessage);

        // Verify the results
        ListKeyAdaptingCaseInsensitiveHeadersMap resultHeaders = new ListKeyAdaptingCaseInsensitiveHeadersMap(httpMessage.headers().asStrictMap());

        assertTrue(resultHeaders.containsKey("Authorization"));
        String authHeader = ((List<String>)resultHeaders.get("Authorization")).get(0);
        assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request"));

        assertEquals("19700101T000000Z", ((List<String>)resultHeaders.get("X-Amz-Date")).get(0));

        if (payload != null) {
            assertTrue(resultHeaders.containsKey("x-amz-content-sha256"));
            String expectedHash = calculateSHA256(payload);
            assertEquals(expectedHash, ((List<String>)resultHeaders.get("x-amz-content-sha256")).get(0));
        } else {
            assertFalse(resultHeaders.containsKey("x-amz-content-sha256"));
        }
    }

    @SneakyThrows
    private String calculateSHA256(String payload) {
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
