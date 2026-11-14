package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class SigV4RequestTransformSigningTest {
    private static final String COLLECTION_HEADER = "x-amz-aoss-collection-name";
    private static final Pattern SIGNED_HEADERS = Pattern.compile("SignedHeaders=([^,\\s]+)");

    @SuppressWarnings("unchecked")
    private static Object addCollectionHeader(Object message) {
        var headers = (Map<String, Object>) ((Map<String, Object>) message).get("headers");
        headers.put(COLLECTION_HEADER, List.of("tenant-a"));
        return message;
    }

    @ParameterizedTest
    @CsvSource({"GET,''", "POST,'{\"a\":1}'"})
    void headerAddedByRequestTransformIsSentAndSigned(String method, String body) throws Exception {
        assertCollectionHeaderSentAndSigned(
            replay(SigV4RequestTransformSigningTest::addCollectionHeader, method, body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"JsonJoltTransformerProvider\":{\"script\":{\"operation\":\"default\","
            + "\"spec\":{\"headers\":{\"x-amz-aoss-collection-name\":\"tenant-a\"}}}}}]",
        "[{\"JsonJSTransformerProvider\":{\"bindingsObject\":\"{}\",\"initializationScript\":"
            + "\"(function(b){return function(m){"
            + "m.get('headers').set('x-amz-aoss-collection-name','tenant-a');return m;};})\"}}]"
    })
    void headerAddedByConfiguredTransformIsSentAndSigned(String transformerConfig) throws Exception {
        var transformer = new TransformationLoader()
            .getTransformerFactoryLoader("123456789012.aoss.us-east-1.on.aws", null, transformerConfig);

        assertCollectionHeaderSentAndSigned(replay(transformer, "POST", "{\"a\":1}"));
    }

    private static void assertCollectionHeaderSentAndSigned(String output) {
        Assertions.assertTrue(output.contains(COLLECTION_HEADER + ": tenant-a\r\n"), output);
        var signedHeaders = SIGNED_HEADERS.matcher(output);
        Assertions.assertTrue(signedHeaders.find(), output);
        Assertions.assertTrue(List.of(signedHeaders.group(1).split(";")).contains(COLLECTION_HEADER), output);
    }

    private static String replay(IJsonTransformer transformer, String method, String body) throws Exception {
        var capture = new TestCapturePacketToHttpHandler(
            Duration.ofMillis(100),
            new AggregatedRawResponse(null, -1, Duration.ZERO, new ArrayList<>(), null)
        );
        var authFactory = new SigV4AuthTransformerFactory(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKey", "secretKey")),
            "aoss",
            "us-east-1",
            "https",
            null
        );
        try (var context = TestContext.noOtelTracking()) {
            var consumer = new HttpJsonTransformingConsumer<>(
                transformer,
                authFactory,
                capture,
                context.getTestConnectionRequestContext("TEST_CONNECTION", 0).createTransformationContext()
            );
            var request = method + " /index/_doc HTTP/1.1\r\n"
                + "Host: 123456789012.aoss.us-east-1.on.aws\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n"
                + body;
            consumer.consumeBytes(request.getBytes(StandardCharsets.UTF_8)).get();
            Object output = consumer.finalizeRequest().get().transformedOutput;
            var producer = (ByteBufListProducer) output;
            var packets = producer.get();
            try {
                return packets.asByteArrayStream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .collect(Collectors.joining());
            } finally {
                packets.release();
                producer.release();
            }
        }
    }
}
