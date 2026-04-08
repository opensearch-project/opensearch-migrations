package org.opensearch.migrations.replay.transform;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datahandlers.http.SigningByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@WrapWithNettyLeakDetection
public class SigV4AuthTransformerFactoryTest extends InstrumentationTest {

    private static class MockCredentialsProvider implements AwsCredentialsProvider {
        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-type", "Content-Type", "CoNteNt-Type"})
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSignatureProperlyApplied(String contentTypeHeaderKey) throws Exception {
        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        var output = runSigningPipeline(contentTypeHeaderKey, "application/json", stringParts);
        var outputStr = output.toString(StandardCharsets.UTF_8);

        Assertions.assertTrue(outputStr.contains("Authorization: AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request"),
            "Expected Authorization header in output: " + outputStr);
        Assertions.assertTrue(outputStr.contains("X-Amz-Date: 19700101T000000Z"),
            "Expected X-Amz-Date header in output: " + outputStr);
        Assertions.assertTrue(outputStr.contains("x-amz-content-sha256:"),
            "Expected x-amz-content-sha256 header in output: " + outputStr);
        output.release();
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testSignatureProperlyAppliedNoPayload() throws Exception {
        var output = runSigningPipeline(null, null, List.of());
        var outputStr = output.toString(StandardCharsets.UTF_8);

        Assertions.assertTrue(outputStr.contains("Authorization: AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/19700101/us-east-1/es/aws4_request"),
            "Expected Authorization header in output: " + outputStr);
        Assertions.assertTrue(outputStr.contains("X-Amz-Date: 19700101T000000Z"),
            "Expected X-Amz-Date header in output: " + outputStr);
        output.release();
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testRepeatedSigningProducesFreshHeaders() throws Exception {
        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        var producer = runSigningPipelineGetProducer("Content-Type", "application/json", stringParts);

        var first = producer.get();
        var firstComposite = first.asCompositeByteBufRetained();
        var firstStr = firstComposite.toString(StandardCharsets.UTF_8);
        firstComposite.release();
        first.release();

        var second = producer.get();
        var secondComposite = second.asCompositeByteBufRetained();
        var secondStr = secondComposite.toString(StandardCharsets.UTF_8);
        secondComposite.release();
        second.release();

        // Both should contain auth headers
        Assertions.assertTrue(firstStr.contains("Authorization:"),
            "First output should contain Authorization header. Got: " + firstStr);
        Assertions.assertTrue(secondStr.contains("Authorization:"),
            "Second output should contain Authorization header. Got: " + secondStr);

        // Both should be identical (same fixed clock)
        Assertions.assertEquals(firstStr, secondStr,
            "With a fixed clock, repeated signing should produce identical output");

        producer.release();
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testReSigningProducesDifferentHeadersWhenClockAdvances() throws Exception {
        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        var clockRef = new java.util.concurrent.atomic.AtomicReference<>(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        try (var factory = new SigV4AuthTransformerFactory(
            new MockCredentialsProvider(), "es", "us-east-1", "https",
            clockRef::get
        )) {
            var producer = buildProducer(factory, "Content-Type", "application/json", stringParts);

            var first = producer.get();
            var firstComposite = first.asCompositeByteBufRetained();
            var firstStr = firstComposite.toString(StandardCharsets.UTF_8);
            firstComposite.release();
            first.release();

            // Advance the clock by 1 hour
            clockRef.set(Clock.fixed(Instant.EPOCH.plusSeconds(3600), ZoneOffset.UTC));

            var second = producer.get();
            var secondComposite = second.asCompositeByteBufRetained();
            var secondStr = secondComposite.toString(StandardCharsets.UTF_8);
            secondComposite.release();
            second.release();

            // Both should have auth headers
            Assertions.assertTrue(firstStr.contains("X-Amz-Date: 19700101T000000Z"),
                "First should have epoch timestamp");
            Assertions.assertTrue(secondStr.contains("X-Amz-Date: 19700101T010000Z"),
                "Second should have advanced timestamp. Got: " + secondStr);

            // Authorization signatures must differ due to different timestamps
            Assertions.assertNotEquals(firstStr, secondStr,
                "Output should differ when clock advances");

            producer.release();
        }
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 2)
    public void testJsonTransformRunsOnceNotOnRetries() throws Exception {
        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
            .mapToObj(i -> TestUtils.makeRandomString(r, 64))
            .collect(Collectors.toList());

        var transformCount = new java.util.concurrent.atomic.AtomicInteger(0);
        IJsonTransformer countingTransformer = msg -> {
            transformCount.incrementAndGet();
            return msg;
        };

        try (var factory = new SigV4AuthTransformerFactory(
            new MockCredentialsProvider(), "es", "us-east-1", "https",
            () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        )) {
            var transformingHandler = new HttpJsonTransformingConsumer<>(
                countingTransformer, factory, new TransformedPacketReceiver(),
                rootContext.getTestConnectionRequestContext("TEST_CONNECTION", 0)
            );

            var contentLength = stringParts.stream().mapToInt(String::length).sum();
            var headerString = "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + contentLength + "\r\n\r\n";

            transformingHandler.consumeBytes(
                Unpooled.wrappedBuffer(headerString.getBytes(StandardCharsets.UTF_8)));
            for (var part : stringParts) {
                transformingHandler.consumeBytes(
                    Unpooled.wrappedBuffer(part.getBytes(StandardCharsets.UTF_8)));
            }

            var result = transformingHandler.finalizeRequest().get();
            var producer = (ByteBufListProducer) result.transformedOutput;

            Assertions.assertEquals(1, transformCount.get(),
                "Transform should have run exactly once during pipeline processing");

            // Call get() multiple times (simulating retries)
            for (int i = 0; i < 3; i++) {
                var packets = producer.get();
                var composite = packets.asCompositeByteBufRetained();
                Assertions.assertTrue(
                    composite.toString(StandardCharsets.UTF_8).contains("Authorization:"),
                    "Retry " + i + " should contain Authorization header");
                composite.release();
                packets.release();
            }

            Assertions.assertEquals(1, transformCount.get(),
                "Transform count should still be 1 after multiple get() calls — "
                + "retries should only re-sign, not re-transform");

            producer.release();
        }
    }

    private io.netty.buffer.CompositeByteBuf runSigningPipeline(
        String contentTypeHeaderKey, String contentTypeHeaderValue,
        List<String> stringParts
    ) throws Exception {
        var producer = runSigningPipelineGetProducer(contentTypeHeaderKey, contentTypeHeaderValue, stringParts);
        var packets = producer.get();
        var result = packets.asCompositeByteBufRetained();
        packets.release();
        // Don't release producer here — caller didn't retain it, and the signing producer
        // manages its own body chunk lifecycle. The test just needs the output bytes.
        return result;
    }

    private ByteBufListProducer runSigningPipelineGetProducer(
        String contentTypeHeaderKey, String contentTypeHeaderValue,
        List<String> stringParts
    ) throws Exception {
        try (var factory = new SigV4AuthTransformerFactory(
            new MockCredentialsProvider(), "es", "us-east-1", "https",
            () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        )) {
            return buildProducer(factory, contentTypeHeaderKey, contentTypeHeaderValue, stringParts);
        }
    }

    private ByteBufListProducer buildProducer(
        SigV4AuthTransformerFactory factory,
        String contentTypeHeaderKey, String contentTypeHeaderValue,
        List<String> stringParts
    ) throws Exception {
        IJsonTransformer identityTransformer = x -> x;
        var transformingHandler = new HttpJsonTransformingConsumer<>(
            identityTransformer, factory, new TransformedPacketReceiver(),
            rootContext.getTestConnectionRequestContext("TEST_CONNECTION", 0)
        );

        var contentLength = stringParts.stream().mapToInt(String::length).sum();
        var headerString = "GET / HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + (contentTypeHeaderKey != null
                ? contentTypeHeaderKey + ": " + contentTypeHeaderValue + "\r\n" : "")
            + "Content-Length: " + contentLength + "\r\n\r\n";

        transformingHandler.consumeBytes(
            Unpooled.wrappedBuffer(headerString.getBytes(StandardCharsets.UTF_8)));
        for (var part : stringParts) {
            transformingHandler.consumeBytes(
                Unpooled.wrappedBuffer(part.getBytes(StandardCharsets.UTF_8)));
        }

        var result = transformingHandler.finalizeRequest().get();
        Assertions.assertInstanceOf(SigningByteBufListProducer.class, result.transformedOutput,
            "Expected SigningByteBufListProducer but got " + result.transformedOutput.getClass().getName());
        return (ByteBufListProducer) result.transformedOutput;
    }
}
