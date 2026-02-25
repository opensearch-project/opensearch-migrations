package org.opensearch.migrations.transform.shim.netty;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline handler that transforms an inbound FullHttpRequest using an IJsonTransformer.
 * Converts the request to a Map, applies the transform, and converts back to a FullHttpRequest.
 * Also handles the /_shim/health endpoint as a short-circuit.
 *
 * Pipeline position: after HttpObjectAggregator, before SigV4SigningHandler.
 */
@Slf4j
public class RequestTransformHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final IJsonTransformer requestTransformer;

    public RequestTransformHandler(IJsonTransformer requestTransformer) {
        super(false); // don't auto-release — we manage lifecycle manually
        this.requestTransformer = requestTransformer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        try {
            var requestMap = HttpMessageUtil.requestToMap(request);

            @SuppressWarnings("unchecked")
            var transformedMap = (Map<String, Object>) requestTransformer.transformJson(requestMap);

            // Health check short-circuit
            String uri = (String) transformedMap.get(JsonKeysForHttpMessage.URI_KEY);
            if ("/_shim/health".equals(uri)) {
                request.release();
                sendHealthResponse(ctx, keepAlive);
                return;
            }

            var transformedRequest = HttpMessageUtil.mapToRequest(transformedMap);
            // Store keep-alive state for downstream handlers
            ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).set(keepAlive);
            request.release();
            ctx.fireChannelRead(transformedRequest);
        } catch (RuntimeException e) {
            request.release();
            log.error("Request transformation failed", e);
            sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Request transformation failed", keepAlive);
        }
    }

    private static void sendHealthResponse(ChannelHandlerContext ctx, boolean keepAlive) {
        var body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        writeResponse(ctx, response, keepAlive);
    }

    static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status,
                           String message, boolean keepAlive) {
        writeResponse(ctx, HttpMessageUtil.errorResponse(status, message), keepAlive);
    }

    private static void writeResponse(ChannelHandlerContext ctx,
                                       io.netty.handler.codec.http.FullHttpResponse response,
                                       boolean keepAlive) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
