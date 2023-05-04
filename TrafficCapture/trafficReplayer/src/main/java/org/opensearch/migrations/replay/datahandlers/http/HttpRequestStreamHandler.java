package org.opensearch.migrations.replay.datahandlers.http;

import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class HttpRequestStreamHandler extends ChannelInboundHandlerAdapter {
    /**
     * This is stored as part of a closure for the pipeline continuation that will be triggered
     * once it becomes apparent if we need to process the payload stream or if we can pass it
     * through as is.
     */
    final IPacketToHttpHandler packetReceiver;
    final JsonTransformer transformer;
    final List<List<Integer>> chunkSizes;
    HttpJsonMessageWithFaultablePayload httpJsonMessage;
    JsonAccumulator payloadAccumulator;
    Consumer<HTTP_CONSUMPTION_STATUS> statusWatcher;

    public HttpRequestStreamHandler(JsonTransformer transformer, List<List<Integer>> chunkSizes,
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
            httpJsonMessage = parseHeadersIntoMessage(request);
            try {
                transformer.transformJson(httpJsonMessage);
                if (headerFieldIsIdentical("content-encoding", request, httpJsonMessage) &&
                        headerFieldIsIdentical("transfer-encoding", request, httpJsonMessage)) {
                    addPassThroughHandlers(ctx);
                } else {
                    addJsonParsingHandlers(ctx);
                }
            } catch (PayloadNotLoadedException pnle) {
                payloadAccumulator = new JsonAccumulator();
                addJsonParsingHandlers(ctx);
            }
            if (this.httpJsonMessage.headers().get("content-length") == null &&
                    this.httpJsonMessage.headers().get("transfer-encoding") == null) {
                statusWatcher.accept(HTTP_CONSUMPTION_STATUS.DONE);
            }
            ctx.fireChannelRead(httpJsonMessage);
        } else if (msg instanceof HttpContent) {
            if (msg instanceof LastHttpContent) {
                statusWatcher.accept(HTTP_CONSUMPTION_STATUS.DONE);
            }
            if (this.httpJsonMessage.headers().get("content-length") == null &&
                    this.httpJsonMessage.headers().get("transfer-encoding") == null) {
                throw new MalformedRequestException();
            }
            if (payloadAccumulator != null) {
                var payloadContent = ((HttpContent) msg).content();
                payloadAccumulator.consumeNextChunk(payloadContent);
            } else {
                ctx.fireChannelRead(msg);
            }
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
        log.info("TODO: Copying header NAMES over through a lowercase transformation that is currently NOT preserved " +
                "when sending the response");
        var headers = request.headers().entries().stream()
                .collect(Collectors.groupingBy(kvp->kvp.getKey().toLowerCase(),
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        jsonMsg.setHeaders(headers);
        jsonMsg.setPayload(new PayloadFaultMap(headers));
        return jsonMsg;
    }

    private void addJsonParsingHandlers(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast(new HttpContentDecompressor());
        ctx.pipeline().addLast(new LoggingHandler(ByteBufFormat.HEX_DUMP));
        ctx.pipeline().addLast(new HttpTransformedRequestSenderHandler(Collections.unmodifiableList(chunkSizes)));
        ctx.pipeline().addLast(new HttpContentCompressor());
        ctx.pipeline().addLast(new FinalByteBufConsumerHandler(packetReceiver));
    }

    private void addPassThroughHandlers(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast(new FinalByteBufConsumerHandler(packetReceiver));
    }

    private List<String> nullIfEmpty(List list) {
        return list != null && list.size() == 0 ? null : list;
    }

    private boolean headerFieldIsIdentical(String headerName, HttpRequest request,
                                           HttpJsonMessageWithFaultablePayload httpJsonMessage) {
        var originalValue = nullIfEmpty(request.headers().getAll(headerName));
        var newValue = nullIfEmpty(httpJsonMessage.headers().get(headerName));
        if (originalValue != null && newValue != null) {
            return originalValue.equals(newValue);
        } else if (originalValue == null && newValue == null) {
            return true;
        } else {
            return false;
        }
    }

}
