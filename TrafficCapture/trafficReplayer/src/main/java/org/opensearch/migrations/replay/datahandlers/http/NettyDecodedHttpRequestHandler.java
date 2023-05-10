package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;
import org.opensearch.migrations.replay.datahandlers.PayloadNotLoadedException;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    //HttpJsonMessageWithFaultablePayload httpJsonMessage;
    Consumer<HTTP_CONSUMPTION_STATUS> statusWatcher;

    public NettyDecodedHttpRequestHandler(JsonTransformer transformer, List<List<Integer>> chunkSizes,
                                          IPacketToHttpHandler packetReceiver,
                                          Consumer<HTTP_CONSUMPTION_STATUS> statusWatcher) {
        this.packetReceiver = packetReceiver;
        this.transformer = transformer;
        this.chunkSizes = chunkSizes;
        this.statusWatcher = statusWatcher;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            chunkSizes.add(new ArrayList<>(32));
            var request = (HttpRequest) msg;
            var httpJsonMessage = parseHeadersIntoMessage(request);
            var pipelineOrchestrator = new RequestPipelineOrchestrator(chunkSizes, packetReceiver);
            var pipeline = ctx.pipeline();
            try {
                transformer.transformJson(httpJsonMessage);
                httpJsonMessage.copyOriginalHeaders(request.headers());
                if (headerFieldIsIdentical("content-encoding", request, httpJsonMessage) &&
                        headerFieldIsIdentical("transfer-encoding", request, httpJsonMessage)) {
                    statusWatcher.accept(HTTP_CONSUMPTION_STATUS.DONE);
                    // TODO - there might be a memory leak here.
                    // I'm not sure if I should be releasing the HttpRequest `msg`.
                    log.warn("TODO - there might be a memory leak here.  " +
                            "I'm not sure if I should be releasing the HttpRequest `msg`.");
                    pipelineOrchestrator.addBaselineHandlers(pipeline);
                    ctx.fireChannelRead(httpJsonMessage);
                    pipelineOrchestrator.removeThisAndPreviousHandlers(pipeline, this);
                    return;
                } else {
                    pipelineOrchestrator.addContentRepackingHandlers(pipeline);
                }
            } catch (PayloadNotLoadedException pnle) {
                // make a fresh message and its headers
                httpJsonMessage = parseHeadersIntoMessage(request);
                httpJsonMessage.copyOriginalHeaders(request.headers());
                pipelineOrchestrator.addJsonParsingHandlers(pipeline);
            }
            // send both!  We'll allow some built-in netty http handlers to do their thing & then
            // reunify any additions with our headers model before serializing
            ctx.fireChannelRead(request);
            ctx.fireChannelRead(httpJsonMessage);
        } else if (msg instanceof HttpContent) {
            if (msg instanceof LastHttpContent) {
                statusWatcher.accept(HTTP_CONSUMPTION_STATUS.DONE);
            }
            ctx.fireChannelRead(msg);
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
        jsonMsg.setPayload(new PayloadFaultMap(headers));
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
