package org.opensearch.migrations.replay.transform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@WrapWithNettyLeakDetection
public class SigV4AuthTransformerFactoryTest extends InstrumentationTest {

    private static class MockCredentialsProvider implements AwsCredentialsProvider {
        @Override
        public AwsCredentials resolveCredentials() {
            // Notice that these are example keys
            return AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-type", "Content-Type", "CoNteNt-Type"})
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSignatureProperlyApplied(String contentTypeHeaderKey) throws Exception {
        Random r = new Random(2);
        var mockCredentialsProvider = new MockCredentialsProvider();

        // Test with payload
        testWithPayload(r, mockCredentialsProvider, contentTypeHeaderKey);
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSignatureProperlyAppliedNoPayload() throws Exception {
        var mockCredentialsProvider = new MockCredentialsProvider();

        // Test without payload
        testWithoutPayload(mockCredentialsProvider);
    }

    private void testWithPayload(Random r, MockCredentialsProvider mockCredentialsProvider, String contentTypeHeaderKey) throws Exception {
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        var contentTypeHeaderValue = "application/json";

        expectedRequestHeaders.add("Host", "localhost");
        expectedRequestHeaders.add(contentTypeHeaderKey, contentTypeHeaderValue);
        expectedRequestHeaders.add("Content-Length", "46");
        expectedRequestHeaders.add(
            "Authorization",
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request, "
                + "SignedHeaders=" + contentTypeHeaderKey.toLowerCase() + ";host;x-amz-content-sha256;x-amz-date, "
                + "Signature=7f321c18069cac1ec6dbbeb4cabeae83ed7fd31724d692f7e486932792c8f82b"
        );
        expectedRequestHeaders.add(
            "x-amz-content-sha256",
            "fc0e8e9a1f7697f510bfdd4d55b8612df8a0140b4210967efd87ee9cb7104362"
        );
        expectedRequestHeaders.add("X-Amz-Date", "19700101T000000Z");
        runTest(mockCredentialsProvider, contentTypeHeaderKey, contentTypeHeaderValue, expectedRequestHeaders, stringParts);
    }

    private void testWithoutPayload(MockCredentialsProvider mockCredentialsProvider) throws Exception {
        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();

        expectedRequestHeaders.add("Host", "localhost");
        expectedRequestHeaders.add("Content-Length", "0");
        expectedRequestHeaders.add("X-Amz-Date", "19700101T000000Z");
        expectedRequestHeaders.add(
            "Authorization",
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request, "
                + "SignedHeaders=" + "host;x-amz-date, "
                + "Signature=0c80b2640a1de42eb5cf957c6bc080731d8f83ccc1d4ebe40e72cd847ed4648e"
        );
        runTest(mockCredentialsProvider, null, null, expectedRequestHeaders, List.of());
    }

    private void runTest(MockCredentialsProvider mockCredentialsProvider, String contentTypeHeaderKey, String contentTypeHeaderValue, DefaultHttpHeaders expectedRequestHeaders, java.util.List<String> stringParts) throws Exception {
        try (var factory = new SigV4AuthTransformerFactory(
            mockCredentialsProvider,
            "es",
            "us-east-1",
            "https",
            () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        )) {
            TestUtils.runPipelineAndValidate(
                rootContext,
                factory,
                (contentTypeHeaderKey != null) ? contentTypeHeaderKey + ": " + contentTypeHeaderValue + "\r\n" : null,
                stringParts,
                expectedRequestHeaders,
                TestUtils::resolveReferenceString
            );
        }
    }
}
