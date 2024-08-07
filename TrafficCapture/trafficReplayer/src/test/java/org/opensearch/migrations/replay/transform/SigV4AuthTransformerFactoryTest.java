package org.opensearch.migrations.replay.transform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.util.ResourceLeakDetector;
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

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSignatureProperlyApplied() throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        var mockCredentialsProvider = new MockCredentialsProvider();
        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)

        // Using unusual spelling to verify SigV4 signer behavior with list insensitive map
        var contentTypeHeaderKey = "CoNteNt-Type";
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

        expectedRequestHeaders.add("x-amz-date", "19700101T000000Z");

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
                contentTypeHeaderKey + ": "+ contentTypeHeaderValue + "\r\n",
                stringParts,
                expectedRequestHeaders,
                TestUtils::resolveReferenceString
            );
        }

    }
}
