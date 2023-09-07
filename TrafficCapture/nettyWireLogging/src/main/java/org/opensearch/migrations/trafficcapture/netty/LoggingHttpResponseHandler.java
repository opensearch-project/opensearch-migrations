package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class LoggingHttpResponseHandler extends ChannelOutboundHandlerAdapter {

    private final IChannelConnectionCaptureSerializer trafficOffloader;
    private static final Logger metricsLogger = LoggerFactory.getLogger("MetricsLogger");


    public LoggingHttpResponseHandler(IChannelConnectionCaptureSerializer trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
    }

    public HttpCaptureSerializerUtil.HttpProcessedState onHttpObjectsDecoded(List<Object> parsedMsgs) throws IOException {
        return HttpCaptureSerializerUtil.addRelevantHttpMessageIndicatorEvents(trafficOffloader, parsedMsgs);
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
        MDC.put("channelId",ctx.channel().id().asLongText());
        metricsLogger.info("Component of response received");
        super.write(ctx, msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        super.flush(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        super.exceptionCaught(ctx, cause);
    }

}
