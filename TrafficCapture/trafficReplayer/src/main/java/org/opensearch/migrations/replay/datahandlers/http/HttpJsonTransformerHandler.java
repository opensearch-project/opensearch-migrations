package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LoggingHandler;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.transform.JsonTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class HttpJsonTransformerHandler implements IPacketToHttpHandler {
    private final EmbeddedChannel channel;
    private final JsonTransformer transformer;
    /**
     * Roughly try to keep track of how big each data chunk was that came into the transformer.  These values
     * are used to chop results up on the way back out.
     * Divide the chunk tracking into headers (index=0) and payload (index=1).
     */
    private final ArrayList<ArrayList<Integer>> chunkSizes;

    public HttpJsonTransformerHandler(JsonTransformer transformer, IPacketToHttpHandler transformedPacketReceiver) {
        this.transformer = transformer;
        channel = new EmbeddedChannel(
                new HttpRequestDecoder(),
                new LoggingHandler(ByteBufFormat.HEX_DUMP)
//                ,new HttpContentDecompressor()
                ,new HttpRequestStreamHandler()
//                ,new LoggingHandler(ByteBufFormat.HEX_DUMP)
//                ,new HttpTransformedRequestSenderHandler()
//                ,new LoggingHandler(ByteBufFormat.HEX_DUMP)
//                ,new HttpContentCompressor()
                ,new EndOfConsumptionHandler(transformedPacketReceiver)
//                ,new LoggingHandler(ByteBufFormat.HEX_DUMP)
        );
        chunkSizes = new ArrayList<>(2);
        chunkSizes.add(new ArrayList<>(4));
    }

    private EndOfConsumptionHandler getEndOfConsumptionHandler() {
        return (EndOfConsumptionHandler) channel.pipeline().last();
    }

    public CompletableFuture<Void> consumeBytes(ByteBuf nextRequestPacket) {
        chunkSizes.get(chunkSizes.size()-1).add(nextRequestPacket.readableBytes());
        if (log.isDebugEnabled()) {
            byte[] copy = new byte[nextRequestPacket.readableBytes()];
            nextRequestPacket.duplicate().readBytes(copy);
            log.debug("Writing into embedded channel: " + new String(copy, StandardCharsets.UTF_8));
        }
        channel.writeInbound(nextRequestPacket);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
        channel.close();
        return getEndOfConsumptionHandler().packetReceiverCompletionFuture;
    }

    public class HttpRequestStreamHandler extends ChannelInboundHandlerAdapter {
        HttpJsonMessageWithFaultablePayload httpJsonMessage;
        JsonAccumulator payloadAccumulator;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest) {
                chunkSizes.add(new ArrayList<>(32));
                var request = (HttpRequest) msg;
                httpJsonMessage = parseHeadersIntoMessage(request);
                transformer.transformJson(httpJsonMessage);
                try {
                    httpJsonMessage.payload().payloadWasLoaded();
                } catch (PayloadNotLoadedException pnle) {
                    payloadAccumulator = new JsonAccumulator();
                }
                ctx.fireChannelRead(httpJsonMessage);
            } else if (msg instanceof HttpContent) {
                if (payloadAccumulator != null) {
                    var payloadContent = ((HttpContent) msg).content();
                    payloadAccumulator.consumeNextChunk(payloadContent);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof ConsumeFinishedSentinel) {
                log.error("Hooray");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof DecoderException) {
                super.exceptionCaught(ctx, cause);
            } else {
                super.exceptionCaught(ctx, cause);
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            log.warn("boom - next");
            super.handlerRemoved(ctx);
        }
    }

    public class HttpTransformedRequestSenderHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpJsonMessageWithFaultablePayload) {
                var byteBufs = writeHeadersIntoByteBufs((HttpJsonMessageWithFaultablePayload) msg);
                for (var bb : byteBufs) {
                    ctx.fireChannelRead(bb);
                }
                super.channelRead(ctx, msg);
            } else {
                super.channelRead(ctx, msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof DecoderException) {
                super.exceptionCaught(ctx, cause);
            } else {
                super.exceptionCaught(ctx, cause);
            }
        }
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ConsumeFinishedSentinel {
        private final int iteration;
    }

    public class EndOfConsumptionHandler extends ChannelInboundHandlerAdapter {
        final IPacketToHttpHandler packetReceiver;

        public final CompletableFuture<AggregatedRawResponse> packetReceiverCompletionFuture;

        public EndOfConsumptionHandler(IPacketToHttpHandler packetReceiver) {
            this.packetReceiver = packetReceiver;
            this.packetReceiverCompletionFuture = new CompletableFuture<>();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            packetReceiver.finalizeRequest()
                    .whenComplete((v,t) -> {
                        if (t != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t);
                        } else {
                            packetReceiverCompletionFuture.complete(v);
                        }
                    });
            super.handlerRemoved(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                packetReceiver.consumeBytes((ByteBuf) msg)
                        .whenComplete((v,t) -> {
                            if (t != null) {
                                packetReceiverCompletionFuture.completeExceptionally(t);
                            } else {
                                try {
                                    super.channelRead(ctx, msg);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
            }
        }
    }

    private static HttpJsonMessageWithFaultablePayload parseHeadersIntoMessage(HttpRequest request) {
        var jsonMsg = new HttpJsonMessageWithFaultablePayload();
        jsonMsg.setUri(request.uri().toString());
        jsonMsg.setMethod(request.method().toString());
        // TODO - pull the exact HTTP version string from the packets.
        // This is an example of where netty moves too far away from the source for our needs
        log.info("TODO: pull the exact HTTP version string from the packets instead of hardcoding it.");
        jsonMsg.setProtocol("HTTP/1.1");
        log.info("TODO: Copying header NAMES over through a lowercase transformation that is currently NOT preserved " +
                "when sending the response");
        var headers = request.headers().entries().stream()
                .collect(Collectors.groupingBy(kvp->kvp.getKey().toLowerCase(),
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        jsonMsg.setHeaders(headers);
        jsonMsg.setPayload(new PayloadFaultMap(headers));
        return jsonMsg;
    }

    private ByteBuf[] writeHeadersIntoByteBufs(HttpJsonMessageWithFaultablePayload httpJson) throws IOException {
        var headerChunkSizes = chunkSizes.get(0);
        try {
            if (headerChunkSizes.size() > 1) {
                return getHeadersAsChunks(httpJson, headerChunkSizes,
                        2 * headerChunkSizes.stream().mapToInt(x->x).sum());
            }
        } catch (Exception e) {
            log.warn("writing headers directly to chunks w/ sizes didn't work: "+e);
        }

        try (var baos = new ByteArrayOutputStream()) {
            writeHeadersIntoStream(httpJson, baos);
            return new ByteBuf[] { Unpooled.wrappedBuffer(baos.toByteArray()) };
        }
    }

    private static ByteBuf[] getHeadersAsChunks(HttpJsonMessageWithFaultablePayload httpJson,
                                                ArrayList<Integer> headerChunkSizes,
                                                int maxLastBufferSize)
            throws IOException
    {
        AtomicInteger counter = new AtomicInteger(headerChunkSizes.size());
        var bufs = headerChunkSizes.stream()
                .map(i -> ByteBufAllocator.DEFAULT.buffer(counter.decrementAndGet()==0?maxLastBufferSize:i))
                .toArray(ByteBuf[]::new);
        ByteBuf cbb = Unpooled.wrappedBuffer(bufs);
        try (var bbos = new ByteBufOutputStream(cbb)) {
            writeHeadersIntoStream(httpJson, bbos);
        }
        return bufs;
    }

    private static void writeHeadersIntoStream(HttpJsonMessageWithFaultablePayload httpJson,
                                               OutputStream os) throws IOException {
        try (var osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.append(httpJson.method());
            osw.append(httpJson.uri());
            osw.append(httpJson.protocol());
            osw.append("\n");

            for (var kvpList : httpJson.headers().entrySet()) {
                var key = kvpList.getKey();
                for (var valueEntry : kvpList.getValue()) {
                    osw.append(key);
                    osw.append(":");
                    osw.append(valueEntry);
                    osw.append("\n");
                }
            }
            osw.append("\n\n");
            osw.flush();
        }
    }
}