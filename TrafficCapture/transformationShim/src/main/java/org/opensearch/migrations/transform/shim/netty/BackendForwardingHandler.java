package org.opensearch.migrations.transform.shim.netty;

import javax.net.ssl.SSLEngine;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.transform.IJsonTransformer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline handler that forwards transformed requests to the backend via a Netty client channel,
 * receives the response, applies the response transform, and sends it back to the frontend.
 *
 * This follows the same pattern as the proxy's FrontsideHandler + BacksideHandler:
 * - Opens a Netty connection to the backend (like BacksideConnectionPool.buildConnectionFuture)
 * - Sends the request to the backend channel
 * - Backend pipeline receives the response and relays it back to the frontend channel
 *
 * Pipeline position: last handler in the frontend pipeline.
 */
@Slf4j
public class BackendForwardingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    private final URI backendUri;
    private final IJsonTransformer responseTransformer;
    private final SslContext backendSslContext;
    private final Duration timeout;
    private final Semaphore concurrencySemaphore;
    private final AtomicInteger activeRequests;

    public BackendForwardingHandler(URI backendUri, IJsonTransformer responseTransformer,
                                     SslContext backendSslContext, Duration timeout,
                                     Semaphore concurrencySemaphore, AtomicInteger activeRequests) {
        super(false); // don't auto-release — we manage lifecycle
        this.backendUri = backendUri;
        this.responseTransformer = responseTransformer;
        this.backendSslContext = backendSslContext;
        this.timeout = timeout;
        this.concurrencySemaphore = concurrencySemaphore;
        this.activeRequests = activeRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!concurrencySemaphore.tryAcquire()) {
            request.release();
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Proxy at capacity");
            return;
        }
        activeRequests.incrementAndGet();

        final Channel frontsideChannel = ctx.channel();
        int port = backendUri.getPort() != -1 ? backendUri.getPort()
            : "https".equalsIgnoreCase(backendUri.getScheme()) ? 443 : 80;

        // Set the Host header for the backend
        request.headers().set(HttpHeaderNames.HOST,
            backendUri.getHost() + (backendUri.getPort() != -1 ? ":" + backendUri.getPort() : ""));

        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    var p = ch.pipeline();
                    if (backendSslContext != null) {
                        SSLEngine sslEngine = backendSslContext.newEngine(ch.alloc());
                        sslEngine.setUseClientMode(true);
                        p.addLast("ssl", new SslHandler(sslEngine));
                    }
                    p.addLast("httpCodec", new HttpClientCodec());
                    p.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                    p.addLast("readTimeout", new ReadTimeoutHandler(
                        timeout.toSeconds(), TimeUnit.SECONDS));
                    p.addLast("responseHandler", new BacksideResponseHandler(
                        frontsideChannel, responseTransformer,
                        concurrencySemaphore, activeRequests));
                }
            });

        b.connect(backendUri.getHost(), port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.debug("Connected to backend {}:{}", backendUri.getHost(), port);
                future.channel().writeAndFlush(request).addListener((ChannelFutureListener) writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        log.error("Failed to write request to backend", writeFuture.cause());
                        releaseResources(request);
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Failed to send to backend");
                        writeFuture.channel().close();
                    }
                });
            } else {
                log.error("Failed to connect to backend {}:{}", backendUri.getHost(), port, future.cause());
                releaseResources(request);
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Backend connection failed");
            }
        });
    }

    private void releaseResources(FullHttpRequest request) {
        if (request.refCnt() > 0) {
            request.release();
        }
        concurrencySemaphore.release();
        activeRequests.decrementAndGet();
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        boolean keepAlive = Boolean.TRUE.equals(
            ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).get());
        RequestTransformHandler.sendError(ctx, status, message, keepAlive);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled exception in backend forwarding handler", cause);
        ctx.close();
    }

    /**
     * Backside handler that receives the backend response, transforms it,
     * and relays it back to the frontend channel.
     * Follows the same pattern as the proxy's BacksideHandler.
     */
    @Slf4j
    static class BacksideResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Channel frontsideChannel;
        private final IJsonTransformer responseTransformer;
        private final Semaphore concurrencySemaphore;
        private final AtomicInteger activeRequests;

        BacksideResponseHandler(Channel frontsideChannel, IJsonTransformer responseTransformer,
                                Semaphore concurrencySemaphore, AtomicInteger activeRequests) {
            this.frontsideChannel = frontsideChannel;
            this.responseTransformer = responseTransformer;
            this.concurrencySemaphore = concurrencySemaphore;
            this.activeRequests = activeRequests;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
            try {
                var responseMap = HttpMessageUtil.responseToMap(backendResponse);
                @SuppressWarnings("unchecked")
                var transformedMap = (Map<String, Object>) responseTransformer.transformJson(responseMap);
                var response = HttpMessageUtil.mapToResponse(transformedMap);

                boolean keepAlive = Boolean.TRUE.equals(
                    frontsideChannel.attr(ShimChannelAttributes.KEEP_ALIVE).get());
                if (keepAlive) {
                    response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
                    frontsideChannel.writeAndFlush(response);
                } else {
                    response.headers().set(HttpHeaderNames.CONNECTION, "close");
                    frontsideChannel.writeAndFlush(response)
                        .addListener(ChannelFutureListener.CLOSE);
                }
            } catch (RuntimeException e) {
                log.error("Response transformation failed", e);
                var errorResponse = HttpMessageUtil.errorResponse(
                    HttpResponseStatus.BAD_GATEWAY, "Response transformation failed");
                frontsideChannel.writeAndFlush(errorResponse)
                    .addListener(ChannelFutureListener.CLOSE);
            } finally {
                concurrencySemaphore.release();
                activeRequests.decrementAndGet();
                ctx.close(); // close backend connection
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Backend response error", cause);
            concurrencySemaphore.release();
            activeRequests.decrementAndGet();
            if (frontsideChannel.isActive()) {
                var errorResponse = HttpMessageUtil.errorResponse(
                    HttpResponseStatus.BAD_GATEWAY, "Backend request failed");
                frontsideChannel.writeAndFlush(errorResponse)
                    .addListener(ChannelFutureListener.CLOSE);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.debug("Backend channel inactive");
            super.channelInactive(ctx);
        }
    }
}
