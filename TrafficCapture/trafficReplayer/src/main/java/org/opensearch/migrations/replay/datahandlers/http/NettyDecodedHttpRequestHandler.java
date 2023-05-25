package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;
import org.opensearch.migrations.replay.datahandlers.PayloadNotLoadedException;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class NettyDecodedHttpRequestHandler extends ChannelInboundHandlerAdapter {
    /**
     * This is stored as part of a closure for the pipeline continuation that will be triggered
     * once it becomes apparent if we need to process the payload stream or if we can pass it
     * through as is.
     */
    final IPacketToHttpHandler packetReceiver;
    final JsonTransformer transformer;
    final List<List<Integer>> chunkSizes;

    public NettyDecodedHttpRequestHandler(JsonTransformer transformer, List<List<Integer>> chunkSizes,
                                          IPacketToHttpHandler packetReceiver) {
        this.packetReceiver = packetReceiver;
        this.transformer = transformer;
        this.chunkSizes = chunkSizes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            // TODO - this is super ugly and sloppy - this has to be improved
            chunkSizes.add(new ArrayList<>(32));
            var request = (HttpRequest) msg;
            var httpJsonMessage = parseHeadersIntoMessage(request);
            var pipelineOrchestrator = new RequestPipelineOrchestrator(chunkSizes, packetReceiver);
            var pipeline = ctx.pipeline();
            try {
                handlePayloadNeutralTransformation(ctx, request, httpJsonMessage, pipelineOrchestrator);
            } catch (PayloadNotLoadedException pnle) {
                log.debug("The transforms for this message require payload manipulation, " +
                        "all content handlers are being loaded.");
                // make a fresh message and its headers
                httpJsonMessage = parseHeadersIntoMessage(request);
                pipelineOrchestrator.addJsonParsingHandlers(pipeline, transformer);
                ctx.fireChannelRead(httpJsonMessage);
            }
        } else if (msg instanceof HttpContent) {
            ctx.fireChannelRead(msg);
        }
    }

    private void handlePayloadNeutralTransformation(ChannelHandlerContext ctx,
                                                    HttpRequest request,
                                                    HttpJsonMessageWithFaultablePayload httpJsonMessage,
                                                    RequestPipelineOrchestrator pipelineOrchestrator)
            throws PayloadNotLoadedException
    {
        var pipeline = ctx.pipeline();
        transformer.transformJson(httpJsonMessage);
        if (headerFieldsAreIdentical(request, httpJsonMessage)) {
            log.info("Transformation isn't necessary.  " +
                    "Clearing pipeline to let the parent context redrive directly.");
            while (pipeline.first() != null) {
                pipeline.removeFirst();
            }
        } else if (headerFieldIsIdentical("content-encoding", request, httpJsonMessage) &&
                headerFieldIsIdentical("transfer-encoding", request, httpJsonMessage)) {
            log.info("There were changes to the headers that will be reformatted through this pipeline, " +
                    "but the content (payload) doesn't need to be transformed.  Content Handlers are not " +
                    "being added to the pipeline");
            pipelineOrchestrator.addBaselineHandlers(pipeline);
            ctx.fireChannelRead(httpJsonMessage);
            pipelineOrchestrator.removeThisAndPreviousHandlers(pipeline, this);
        } else {
            pipelineOrchestrator.addContentRepackingHandlers(pipeline);
            ctx.fireChannelRead(httpJsonMessage);
        }
    }

    private boolean headerFieldsAreIdentical(HttpRequest request, HttpJsonMessageWithFaultablePayload httpJsonMessage) {
        if (!request.uri().equals(httpJsonMessage.uri()) ||
                !request.method().toString().equals(httpJsonMessage.method())) {
            return false;
        }
        for (var headerName : httpJsonMessage.headers().keySet()) {
            if (!headerFieldIsIdentical(headerName, request, httpJsonMessage)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof DecoderException) {
            super.exceptionCaught(ctx, cause);
        } else {
            super.exceptionCaught(ctx, cause);
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
        var headers = request.headers().entries().stream()
                .collect(Collectors.groupingBy(kvp -> kvp.getKey(),
                        () -> new StrictCaseInsensitiveHttpHeadersMap(),
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        jsonMsg.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(headers));
        jsonMsg.setPayloadFaultMap(new PayloadFaultMap(headers));
        return jsonMsg;
    }

    private List<String> nullIfEmpty(List list) {
        return list != null && list.size() == 0 ? null : list;
    }

    private boolean headerFieldIsIdentical(String headerName, HttpRequest request,
                                           HttpJsonMessageWithFaultablePayload httpJsonMessage) {
        var originalValue = nullIfEmpty(request.headers().getAll(headerName));
        var newValue = nullIfEmpty(httpJsonMessage.headers().asStrictMap().get(headerName));
        if (originalValue != null && newValue != null) {
            return originalValue.equals(newValue);
        } else if (originalValue == null && newValue == null) {
            return true;
        } else {
            return false;
        }
    }

}
