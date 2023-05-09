package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class RequestPipelineOrchestrator {
    private final List<List<Integer>> chunkSizes;
    private final IPacketToHttpHandler packetReceiver;

    public RequestPipelineOrchestrator(List<List<Integer>> chunkSizes, IPacketToHttpHandler packetReceiver) {
        this.chunkSizes = chunkSizes;
        this.packetReceiver = packetReceiver;
    }

    void addInitialHandlers(ChannelPipeline pipeline,
                            JsonTransformer transformer,
                            Consumer<HTTP_CONSUMPTION_STATUS> statusWatcher) {
        pipeline.addFirst(new HttpRequestDecoder()
                //,new LoggingHandler(ByteBufFormat.HEX_DUMP)
                ,new NettyDecodedHttpRequestHandler(transformer, chunkSizes, packetReceiver, statusWatcher)
        //,new LoggingHandler(ByteBufFormat.HEX_DUMP)
        );
    }

    void addContentRepackingHandlers(ChannelPipeline pipeline) {
        addContentParsingHandlers(pipeline, false);
    }

    void addJsonParsingHandlers(ChannelPipeline pipeline) {
        addContentParsingHandlers(pipeline, true);
    }

    void addContentParsingHandlers(ChannelPipeline pipeline,
                                           boolean addFullJsonTransformer) {
        log.warn("Adding handlers to pipeline");
        // IN: HttpJsonMessage with headers and HttpContent blocks (which may be compressed)
        // OUT: HttpJsonMessage with headers and HttpContent uncompressed blocks
        pipeline.addLast(new HttpContentDecompressor());
        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        if (addFullJsonTransformer) {
            log.warn("Adding JSON handlers to pipeline");
            // IN: HttpJsonMessage with headers and HttpContent blocks that are aggregated into to the payload of the HttpJsonMessage
            // OUT: HttpJsonMessage with headers and payload
            pipeline.addLast(new NettyJsonBodyAccumulateHandler());
            // IN: HttpJsonMessage with headers and payload
            // OUT: HttpJsonMessage with headers and payload, but transformed by the json transformer
            pipeline.addLast(new NettyJsonBodyConvertHandler());
            // IN: HttpJsonMessage with headers and payload
            // OUT: HttpJsonMessage with headers only + HttpContent blocks
            pipeline.addLast(new NettyJsonBodySerializeHandler());
        }
        // IN: HttpJsonMessage with headers only + HttpContent blocks
        // OUT:
        pipeline.addLast(new NettyJsonHeaderToNettyHeaderHandler());
//        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        // IN: HttpJsonMessage with headers only + HttpContent blocks
        // OUT: Same as IN, but HttpContent blocks may be compressed
        pipeline.addLast(new NettyJsonContentCompressor());
        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
//        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        addBaselineHandlers(pipeline);
    }

    void addBaselineHandlers(ChannelPipeline pipeline) {
//        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        // IN: HttpJsonMessage with headers only + (EITHER HttpContent blocks OR ByteBufs)
        // OUT: ByteBufs that attempt to match the sizing of the original chunkSizes
        pipeline.addLast(new NettyJsonToByteBufHandler(Collections.unmodifiableList(chunkSizes)));
//        pipeline.addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        // IN: ByteBufs that attempt to match the sizing of the original chunkSizes
        // OUT: nothing - terminal.  ByteBufs are routed to the packet handler.
        pipeline.addLast(new NettySendByteBufsToPacketHandlerHandler(packetReceiver));
    }

    static void removeThisAndPreviousHandlers(ChannelPipeline pipeline, ChannelHandler beforeHandler) {
        do {
            var h = pipeline.removeFirst();
            if (h == beforeHandler) {
                break;
            }
        } while (true);
    }
}
