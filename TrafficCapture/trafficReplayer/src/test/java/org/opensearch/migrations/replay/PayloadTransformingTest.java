package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformBuilder;
import org.opensearch.migrations.transform.JsonTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class PayloadTransformingTest {
    @Test
    public void testSimplePayloadTransform() throws ExecutionException, InterruptedException, IOException {
        var referenceStringBuilder = new StringBuilder();
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), null);
        var transformingHandler = new HttpJsonTransformer(
                JsonTransformer.newBuilder()
                        .addCannedOperation(JsonTransformBuilder.CANNED_OPERATIONS.ADD_GZIP)
                        .build(), testPacketCapture);

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1)
                .mapToObj(i -> TestUtils.makeRandomString(r, 64))
                .map(o -> (String) o)
                .collect(Collectors.toList());

        var contentLength = stringParts.stream().mapToInt(s->s.length()).sum();
        var headerString = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                "content-length: " + contentLength + "\n\n";
        var allConsumesFuture = TestUtils.chainedWriteHeadersAndDualWritePayloadParts(transformingHandler, stringParts,
                referenceStringBuilder, headerString);

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(v -> transformingHandler.finalizeRequest());
        finalizationFuture.whenComplete((aggregatedRawResponse, t) -> {
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
        });
        finalizationFuture.get();
        verifyResponse(testPacketCapture.getBytesCaptured(), TestUtils.resolveReferenceString(referenceStringBuilder));
    }

    private void verifyResponse(byte[] bytesCaptured, String originalPayloadString) throws IOException {
        log.warn("\n\nBeginning verification pipeline\n\n");

        AtomicReference<FullHttpRequest> fullHttpRequestAtomicReference = new AtomicReference<>();
        EmbeddedChannel unpackVerifier = new EmbeddedChannel(
                new LoggingHandler(),
                new HttpRequestDecoder(),
                new LoggingHandler(),
                new HttpObjectAggregator(bytesCaptured.length*2),
                new LoggingHandler(),
                new HttpContentDecompressor(),
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

        DefaultHttpHeaders expectedRequestHeaders = new DefaultHttpHeaders();
        // this one is removed by the http content decompressor
        // expectedRequestHeaders.add("transfer-encoding", "gzip");
        expectedRequestHeaders.add("host", "localhost");
        expectedRequestHeaders.add("Content-Transfer-Encoding", "chunked");
        expectedRequestHeaders.add("Content-Length", "0");
        Assertions.assertEquals(expectedRequestHeaders, fullRequest.headers());
        Assertions.assertEquals((originalPayloadString), getStringFromContent(fullRequest));
        fullRequest.release();
    }

    private static String getStringFromContent(FullHttpRequest fullRequest) throws IOException {
        try (var baos = new ByteArrayOutputStream()) {
            var bb = fullRequest.content();
            bb.readBytes(baos, bb.readableBytes());
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
