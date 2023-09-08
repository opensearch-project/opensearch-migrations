package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.transform.JsonJoltTransformer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SigV4SigningTransformationTest {

    private static class MockCredentialsProvider implements AwsCredentialsProvider {
        @Override
        public AwsCredentials resolveCredentials() {
            // Notice that these are example keys
            return AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE",
                    "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        }
    }

    @Test
    public void testSignatureProperlyApplied() throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
                .mapToObj(i -> TestUtils.makeRandomString(r, 64))
                .map(o -> (String) o)
                .collect(Collectors.toList());

        var mockCredentialsProvider = new MockCredentialsProvider();
        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // netty's decompressor and aggregator remove some header values (& add others)
        expectedRequestHeaders.add("host", "localhost");
        expectedRequestHeaders.add("Content-Length".toLowerCase(), "46");
        expectedRequestHeaders.add("Authorization",
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request, " +
                        "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                        "Signature=4cb1c423e6fe61216fbaa11398260af7f8daa85e74cd41428711e4df5cd70c97");
        expectedRequestHeaders.add("x-amz-content-sha256",
                        "fc0e8e9a1f7697f510bfdd4d55b8612df8a0140b4210967efd87ee9cb7104362");
        expectedRequestHeaders.add("X-Amz-Date", "19700101T000000Z");

        TestUtils.runPipelineAndValidate(JsonJoltTransformer.newBuilder().build(),
                msg -> new SigV4Signer(mockCredentialsProvider, "es", "us-east-1", "https",
                        () ->  Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                null, stringParts, expectedRequestHeaders,
                referenceStringBuilder -> TestUtils.resolveReferenceString(referenceStringBuilder));
    }
}
