package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class HttpJsonTransformer implements IPacketToHttpHandler {
    private final EmbeddedChannel channel;
    /**
     * Roughly try to keep track of how big each data chunk was that came into the transformer.  These values
     * are used to chop results up on the way back out.
     * Divide the chunk tracking into headers (index=0) and payload (index=1).
     */
    private final List<List<Integer>> chunkSizes;

    HTTP_CONSUMPTION_STATUS handlerStatus;

    public HttpJsonTransformer(JsonTransformer transformer, IPacketToHttpHandler transformedPacketReceiver) {
        chunkSizes = new ArrayList<>(2);
        chunkSizes.add(new ArrayList<>(4));
        channel = new EmbeddedChannel(
                new HttpRequestDecoder(),
                new LoggingHandler(ByteBufFormat.HEX_DUMP)
                ,new NettyDecodedHttpRequestHandler(transformer, chunkSizes, transformedPacketReceiver, s -> {handlerStatus = s;})
                ,new LoggingHandler(ByteBufFormat.HEX_DUMP)
        );
    }

    private NettySendByteBufsToPacketHandlerHandler getEndOfConsumptionHandler() {
        var last = channel.pipeline().last();
        return (NettySendByteBufsToPacketHandlerHandler) last;
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
        var consumerHandler = getEndOfConsumptionHandler();
        channel.close();
        if (handlerStatus != HTTP_CONSUMPTION_STATUS.DONE) {
            return CompletableFuture.failedFuture(new MalformedRequestException());
        }
        return consumerHandler.packetReceiverCompletionFuture;
    }
}