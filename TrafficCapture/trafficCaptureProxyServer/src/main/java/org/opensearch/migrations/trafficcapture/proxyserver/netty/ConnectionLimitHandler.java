package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Limits the number of active connections to the proxy. When the limit is exceeded,
 * new connections are immediately closed with a 503 Service Unavailable response,
 * providing back-pressure signaling to clients.
 */
@Slf4j
@ChannelHandler.Sharable
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {
    private static final byte[] SERVICE_UNAVAILABLE_RESPONSE =
        ("HTTP/1.1 503 Service Unavailable\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: close\r\n"
            + "\r\n").getBytes();

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final int maxConnections;

    public ConnectionLimitHandler(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int current = activeConnections.incrementAndGet();
        if (current > maxConnections) {
            activeConnections.decrementAndGet();
            log.atWarn().setMessage("Connection limit reached ({}/{}), rejecting connection from {}")
                .addArgument(current - 1).addArgument(maxConnections)
                .addArgument(ctx.channel().remoteAddress()).log();
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SERVICE_UNAVAILABLE_RESPONSE))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.decrementAndGet();
        super.channelInactive(ctx);
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }
}
