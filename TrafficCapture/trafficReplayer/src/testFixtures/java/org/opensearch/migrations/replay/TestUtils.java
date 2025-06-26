package org.opensearch.migrations.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.replay.datahandlers.IPacketConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;

@Slf4j
public class TestUtils {

    private TestUtils() {}

    public static String resolveReferenceString(StringBuilder referenceStringBuilder) {
        return resolveReferenceString(referenceStringBuilder, List.of());
    }

    public static String resolveReferenceString(
        StringBuilder referenceStringBuilder,
        Collection<AbstractMap.SimpleEntry<String, String>> replacementMappings
    ) {
        for (var kvp : replacementMappings) {
            var idx = referenceStringBuilder.indexOf(kvp.getKey());
            referenceStringBuilder.replace(idx, idx + kvp.getKey().length(), kvp.getValue());
        }
        return referenceStringBuilder.toString();
    }

    public static String makeRandomString(Random r, int maxStringSize) {
        return r.ints(r.nextInt(maxStringSize), 'A', 'Z')
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    public static TrackedFuture<String, Void> writeStringToBoth(
        String s,
        StringBuilder referenceStringBuilder,
        IPacketConsumer transformingHandler
    ) {
        log.info("Sending string to transformer: " + s);
        referenceStringBuilder.append(s);
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        return transformingHandler.consumeBytes(bytes);
    }

    public static TrackedFuture<String, Void> chainedWriteHeadersAndDualWritePayloadParts(
        IPacketConsumer packetConsumer,
        List<String> stringParts,
        StringBuilder referenceStringAccumulator,
        String headers
    ) {
        return stringParts.stream()
            .collect(
                Utils.foldLeft(
                    packetConsumer.consumeBytes(headers.getBytes(StandardCharsets.UTF_8)),
                    (cf, s) -> cf.thenCompose(
                        v -> writeStringToBoth(s, referenceStringAccumulator, packetConsumer),
                        () -> "TestUtils.chainedWriteHeadersAndDualWritePayloadParts"
                    )
                )
            );
    }

    public static TrackedFuture<String, Void> chainedDualWriteHeaderAndPayloadParts(
        IPacketConsumer packetConsumer,
        List<String> stringParts,
        StringBuilder referenceStringAccumulator,
        IntFunction<String> headersGenerator
    ) {
        var contentLength = stringParts.stream().mapToInt(String::length).sum();
        String headers = headersGenerator.apply(contentLength) + "\r\n";
        referenceStringAccumulator.append(headers);
        return chainedWriteHeadersAndDualWritePayloadParts(
            packetConsumer,
            stringParts,
            referenceStringAccumulator,
            headers
        );
    }

    public static void verifyCapturedResponseMatchesExpectedPayload(
        byte[] bytesCaptured,
        DefaultHttpHeaders expectedRequestHeaders,
        String expectedPayloadString
    ) throws IOException {
        log.warn("\n\nBeginning verification pipeline\n\n");

        AtomicReference<FullHttpRequest> fullHttpRequestAtomicReference = new AtomicReference<>();
        EmbeddedChannel unpackVerifier = new EmbeddedChannel(
            new HttpRequestDecoder(),
            new HttpContentDecompressor(0),
            new HttpObjectAggregator(bytesCaptured.length * 2),
            new SimpleChannelInboundHandler<FullHttpRequest>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                    fullHttpRequestAtomicReference.set(msg.retainedDuplicate());
                }
            }
        );
        unpackVerifier.writeInbound(Unpooled.wrappedBuffer(bytesCaptured));
        Assertions.assertNotNull(fullHttpRequestAtomicReference.get());
        var fullRequest = fullHttpRequestAtomicReference.get();

        Assertions.assertEquals((expectedPayloadString), getStringFromContent(fullRequest));
        Assertions.assertEquals(expectedRequestHeaders, fullRequest.headers());
        // Test casing on keys match
        Assertions.assertEquals(expectedRequestHeaders.names().stream().sorted().collect(Collectors.toList()),
            fullRequest.headers().names().stream().sorted().collect(Collectors.toList()));
        fullRequest.release();
    }

    private static String getStringFromContent(FullHttpRequest fullRequest) {
        return fullRequest.content().toString(StandardCharsets.UTF_8);
    }

    public static void runPipelineAndValidate(
        TestContext rootContext,
        IAuthTransformerFactory authTransformer,
        String extraHeaders,
        List<String> stringParts,
        DefaultHttpHeaders expectedRequestHeaders,
        Function<StringBuilder, String> expectedOutputGenerator
    ) throws IOException, ExecutionException, InterruptedException {
        runPipelineAndValidate(
            rootContext,
            x -> x,
            authTransformer,
            extraHeaders,
            stringParts,
            expectedRequestHeaders,
            expectedOutputGenerator
        );
    }

    public static void runPipelineAndValidate(
        TestContext rootContext,
        IJsonTransformer transformer,
        IAuthTransformerFactory authTransformer,
        String extraHeaders,
        List<String> stringParts,
        DefaultHttpHeaders expectedRequestHeaders,
        Function<StringBuilder, String> expectedOutputGenerator
    ) throws IOException, ExecutionException, InterruptedException {
        var testPacketCapture = new TestCapturePacketToHttpHandler(
            Duration.ofMillis(100),
            new AggregatedRawResponse(null, -1, Duration.ZERO, new ArrayList<>(), null)
        );
        var transformingHandler = new HttpJsonTransformingConsumer<>(
            transformer,
            authTransformer,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext("TEST_CONNECTION", 0)
        );

        var contentLength = stringParts.stream().mapToInt(String::length).sum();
        var headerString = "GET / HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + (extraHeaders == null ? "" : extraHeaders)
            + "Content-Length: "
            + contentLength
            + "\r\n\r\n";
        var referenceStringBuilder = new StringBuilder();
        var allConsumesFuture = chainedWriteHeadersAndDualWritePayloadParts(
            transformingHandler,
            stringParts,
            referenceStringBuilder,
            headerString
        );

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(
            v -> transformingHandler.finalizeRequest(),
            () -> "PayloadRepackingTest.runPipelineAndValidate.allConsumeFuture"
        );
        finalizationFuture.map(f -> f.whenComplete((aggregatedRawResponse, t) -> {
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
        }), () -> "PayloadRepackingTest.runPipelineAndValidate.assertCheck").get();

        verifyCapturedResponseMatchesExpectedPayload(
            testPacketCapture.getBytesCaptured(),
            expectedRequestHeaders,
            expectedOutputGenerator.apply(referenceStringBuilder)
        );
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());
    }
}
