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
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.opensearch.migrations.replay.datahandlers.IPacketConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
public class TestUtils {

    static String resolveReferenceString(StringBuilder referenceStringBuilder) {
        return resolveReferenceString(referenceStringBuilder, List.of());
    }

    static String resolveReferenceString(StringBuilder referenceStringBuilder,
                                         Collection<AbstractMap.SimpleEntry<String,String>> replacementMappings) {
        for (var kvp : replacementMappings) {
            var idx = referenceStringBuilder.indexOf(kvp.getKey());
            referenceStringBuilder.replace(idx, idx + kvp.getKey().length(), kvp.getValue());
        }
        return referenceStringBuilder.toString();
    }

    static String makeRandomString(Random r, int maxStringSize) {
        return r.ints(r.nextInt(maxStringSize), 'A', 'Z')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    static CompletableFuture<Void> writeStringToBoth(String s, StringBuilder referenceStringBuilder,
                                                     IPacketConsumer transformingHandler) {
        log.info("Sending string to transformer: "+s);
        referenceStringBuilder.append(s);
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        return transformingHandler.consumeBytes(bytes);
    }

    static CompletableFuture<Void> chainedWriteHeadersAndDualWritePayloadParts(IPacketConsumer packetConsumer,
                                                                               List<String> stringParts,
                                                                               StringBuilder referenceStringAccumulator,
                                                                               String headers) {
        return stringParts.stream().collect(
                Utils.foldLeft(packetConsumer.consumeBytes(headers.getBytes(StandardCharsets.UTF_8)),
                        (cf, s) -> cf.thenCompose(v -> writeStringToBoth(s, referenceStringAccumulator, packetConsumer))));
    }

    public static CompletableFuture<Void>
    chainedDualWriteHeaderAndPayloadParts(IPacketConsumer packetConsumer,
                                          List<String> stringParts,
                                          StringBuilder referenceStringAccumulator,
                                          Function<Integer, String> headersGenerator) {
        var contentLength = stringParts.stream().mapToInt(s->s.length()).sum();
        String headers = headersGenerator.apply(contentLength) + "\n";
        referenceStringAccumulator.append(headers);
        return chainedWriteHeadersAndDualWritePayloadParts(packetConsumer, stringParts, referenceStringAccumulator, headers);
    }

    public static void verifyCapturedResponseMatchesExpectedPayload(byte[] bytesCaptured,
                                                             DefaultHttpHeaders expectedRequestHeaders,
                                                             String expectedPayloadString)
            throws IOException
    {
        log.warn("\n\nBeginning verification pipeline\n\n");

        AtomicReference<FullHttpRequest> fullHttpRequestAtomicReference = new AtomicReference<>();
        EmbeddedChannel unpackVerifier = new EmbeddedChannel(
                new LoggingHandler(LogLevel.ERROR),
                new HttpRequestDecoder(),
                new LoggingHandler(LogLevel.WARN),
                new HttpContentDecompressor(),
                new LoggingHandler(LogLevel.INFO),
                new HttpObjectAggregator(bytesCaptured.length*2),
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
