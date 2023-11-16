package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.net.SocketAddress;
import java.time.Instant;

@Slf4j
public class LoggingHttpResponseHandler<T> extends ChannelOutboundHandlerAdapter {

    private final IChannelConnectionCaptureSerializer<T> trafficOffloader;
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpResponseHandler");


    public LoggingHttpResponseHandler(IChannelConnectionCaptureSerializer<T> trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addBindEvent(Instant.now(), localAddress);
        super.bind(ctx, localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addConnectEvent(Instant.now(), remoteAddress, localAddress);
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDisconnectEvent(Instant.now());
        super.disconnect(ctx, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        super.close(ctx, promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDeregisterEvent(Instant.now());
        super.deregister(ctx, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        trafficOffloader.addWriteEvent(Instant.now(), (ByteBuf) msg);
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_RESPONSE_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
        super.write(ctx, msg, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        flush(ctx);
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        super.exceptionCaught(ctx, cause);
    }

}
