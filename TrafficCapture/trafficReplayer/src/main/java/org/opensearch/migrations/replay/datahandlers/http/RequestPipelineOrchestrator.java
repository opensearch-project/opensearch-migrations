package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.http.helpers.LastHttpContentListener;
import org.opensearch.migrations.replay.datahandlers.http.helpers.ReadMeteringHandler;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is meant to be the single place for all pipeline manipulations for HttpRequests.
 * Comments are strewn through pipeline additions that show the types of messages that are
 * expected as input and what may be possibly output.  In many cases a message may be transformed
 * while still maintaining its type.  For example, HttpContent might have been compressed going
 * into a decompressor handler and uncompressed coming out.  Both input and outputs will be
 * packaged as HttpContent objects, though there contents will be different.
 *
 * Each handler where the content may undergo changes will show an incremented value for a nonce
 * for that type.  e.g. &quot;HttpContent(1)&quot; vs &quot;HttpContent(2)&quot;.
 */
@Slf4j
public class RequestPipelineOrchestrator<R> {
    /**
     * Set this to of(LogLevel.ERROR) or whatever level you'd like to get logging between each handler.
     * Set this to Optional.empty() to disable intra-handler logging.
     */
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();
    public static final String OFFLOADING_HANDLER_NAME = "OFFLOADING_HANDLER";
    public static final String HTTP_REQUEST_DECODER_NAME = "HTTP_REQUEST_DECODER";
    private final List<List<Integer>> chunkSizes;
    final IPacketFinalizingConsumer<R> packetReceiver;
    private final IReplayContexts.IRequestTransformationContext httpTransactionContext;
    @Getter
    final IAuthTransformerFactory authTransfomerFactory;

    public RequestPipelineOrchestrator(
        List<List<Integer>> chunkSizes,
        IPacketFinalizingConsumer<R> packetReceiver,
        IAuthTransformerFactory incomingAuthTransformerFactory,
        IReplayContexts.IRequestTransformationContext httpTransactionContext
    ) {
        this.chunkSizes = chunkSizes;
        this.packetReceiver = packetReceiver;
        this.authTransfomerFactory = incomingAuthTransformerFactory != null
            ? incomingAuthTransformerFactory
            : IAuthTransformerFactory.NullAuthTransformerFactory.instance;
        this.httpTransactionContext = httpTransactionContext;
    }

    static void removeThisAndPreviousHandlers(ChannelPipeline pipeline, ChannelHandler targetHandler) {
        var precedingHandlers = new ArrayList<ChannelHandler>();
        for (var kvp : pipeline) {
            precedingHandlers.add(kvp.getValue());
            if (kvp.getValue() == targetHandler) {
                break;
            }
        }
        Collections.reverse(precedingHandlers);
        for (var h : precedingHandlers) {
            pipeline.remove(h);
        }
    }

    static void removeAllHandlers(ChannelPipeline pipeline) {
        while (pipeline.first() != null) {
            pipeline.removeLast();
        }
    }

    void addContentRepackingHandlers(
        ChannelHandlerContext ctx,
        IAuthTransformer.StreamingFullMessageTransformer authTransfomer
    ) {
        addContentParsingHandlers(ctx, null, authTransfomer);
    }

    void addJsonParsingHandlers(
        ChannelHandlerContext ctx,
        IJsonTransformer transformer,
        IAuthTransformer.StreamingFullMessageTransformer authTransfomer
    ) {
        addContentParsingHandlers(ctx, transformer, authTransfomer);
    }

    void addInitialHandlers(ChannelPipeline pipeline, IJsonTransformer transformer) {
        pipeline.addFirst(HTTP_REQUEST_DECODER_NAME, new HttpRequestDecoder());
        addLoggingHandler(pipeline, "A");
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::aggregateInputChunk));
        // IN: Netty HttpRequest(1) + HttpContent(1) blocks (which may be compressed) + EndOfInput + ByteBuf
        // OUT: ByteBufs(1) OR Netty HttpRequest(1) + HttpJsonMessage(1) with only headers PLUS + HttpContent(1) blocks
        // Note1: original Netty headers are preserved so that HttpContentDecompressor can work appropriately.
        // HttpJsonMessage is used so that we can capture the headers exactly as they were and to
        // observe packet sizes.
        // Note2: This handler may remove itself and all other handlers and replace the pipeline ONLY with the
        // "baseline" handlers. In that case, the pipeline will be processing only ByteBufs, hence the
        // reason that there's some branching in the types that different handlers consume.
        // Note3: ByteBufs will be sent through when there were pending bytes left to be parsed by the
        // HttpRequestDecoder when the HttpRequestDecoder is removed from the pipeline BEFORE the
        // NettyDecodedHttpRequestHandler is removed.
        pipeline.addLast(
            new NettyDecodedHttpRequestPreliminaryConvertHandler<R>(
                transformer,
                chunkSizes,
                this,
                httpTransactionContext
            )
        );
        addLoggingHandler(pipeline, "B");
    }

    void addContentParsingHandlers(
        ChannelHandlerContext ctx,
        IJsonTransformer transformer,
        IAuthTransformer.StreamingFullMessageTransformer authTransfomer
    ) {
        httpTransactionContext.onPayloadParse();
        log.debug("Adding content parsing handlers to pipeline");
        var pipeline = ctx.pipeline();
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::onPayloadBytesIn));
        // IN: Netty HttpRequest(1) + HttpJsonMessage(1) with headers + HttpContent(1) blocks (which may be compressed)
        // OUT: Netty HttpRequest(2) + HttpJsonMessage(1) with headers + HttpContent(2) uncompressed blocks
        pipeline.addLast(new HttpContentDecompressor());
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::onUncompressedBytesIn));
        if (transformer != null) {
            httpTransactionContext.onJsonPayloadParseRequired();
            log.debug("Adding JSON handlers to pipeline");
            // IN: Netty HttpRequest(2) + HttpJsonMessage(1) with headers + HttpContent(2) blocks
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(2) with headers AND payload
            addLoggingHandler(pipeline, "C");
            pipeline.addLast(new NettyJsonBodyAccumulateHandler(httpTransactionContext));
            // IN: Netty HttpRequest(2) + HttpJsonMessage(2) with headers AND payload
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(3) with headers AND payload (transformed)
            pipeline.addLast(new NettyJsonBodyConvertHandler(transformer));
            // IN: Netty HttpRequest(2) + HttpJsonMessage(3) with headers AND payload
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(3) with headers only + HttpContent(3) blocks
            pipeline.addLast(new NettyJsonBodySerializeHandler());
            addLoggingHandler(pipeline, "F");
        }
        if (authTransfomer != null) {
            pipeline.addLast(new NettyJsonContentAuthSigner(authTransfomer));
            addLoggingHandler(pipeline, "G");
        }
        pipeline.addLast(new LastHttpContentListener(httpTransactionContext::onPayloadParseSuccess));
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::onUncompressedBytesOut));
        // IN: Netty HttpRequest(2) + HttpJsonMessage(3) with headers only + HttpContent(3) blocks
        // OUT: Netty HttpRequest(3) + HttpJsonMessage(4) with headers only + HttpContent(4) blocks
        pipeline.addLast(new NettyJsonContentCompressor());
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::onFinalBytesOut));
        addLoggingHandler(pipeline, "H");
        // IN: Netty HttpRequest(3) + HttpJsonMessage(4) with headers only + HttpContent(4) blocks + EndOfInput
        // OUT: Netty HttpRequest(3) + HttpJsonMessage(4) with headers only + ByteBufs(2)
        pipeline.addLast(new NettyJsonContentStreamToByteBufHandler());
        addLoggingHandler(pipeline, "I");
        addBaselineHandlers(pipeline);
    }

    void addBaselineHandlers(ChannelPipeline pipeline) {
        addLoggingHandler(pipeline, "J");
        // IN: ByteBufs(2) + HttpJsonMessage(4) with headers only + HttpContent(1) (if the repackaging handlers were
        // skipped)
        // OUT: ByteBufs(3) which are sized similarly to how they were received
        pipeline.addLast(new NettyJsonToByteBufHandler(Collections.unmodifiableList(chunkSizes)));
        pipeline.addLast(new ReadMeteringHandler(httpTransactionContext::aggregateOutputChunk));
        // IN: ByteBufs(3)
        // OUT: nothing - terminal! ByteBufs are routed to the packet handler!
        addLoggingHandler(pipeline, "K");
        pipeline.addLast(
            OFFLOADING_HANDLER_NAME,
            new NettySendByteBufsToPacketHandlerHandler<R>(
                packetReceiver,
                httpTransactionContext.getLogicalEnclosingScope()
            )
        );
    }

    private void addLoggingHandler(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(logLevel -> pipeline.addLast(new LoggingHandler("t" + name, logLevel)));
    }
}
