package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.time.Instant;

@Slf4j
public class LoggingHttpResponseHandler<T> extends ChannelOutboundHandlerAdapter {
    public static final String TELEMETRY_SCOPE_NAME = "LoggingHttpOutboundHandler";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpResponseHandler");

    private final IChannelConnectionCaptureSerializer<T> trafficOffloader;
    private ConnectionContext telemetryContext;

    public LoggingHttpResponseHandler(ConnectionContext incomingContext,
                                      IChannelConnectionCaptureSerializer<T> trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
        this.telemetryContext = incomingContext;
    }
//
//    @Override
//    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//        trafficOffloader.addConnectEvent(Instant.now(), remoteAddress, localAddress);
//
//        telemetryContext = new ConnectionContext(telemetryContext,
//                METERING_CLOSURE.makeSpanContinuation("backendConnection"));
//        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "connect");
//        METERING_CLOSURE.meterDeltaEvent(telemetryContext, "connections", 1);
//
//        super.connect(ctx, remoteAddress, localAddress, promise);
//    }

//    @Override
//    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
//        trafficOffloader.addDisconnectEvent(Instant.now());
//        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "disconnect");
//        super.disconnect(ctx, promise);
//    }

//    @Override
//    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
//        trafficOffloader.addCloseEvent(Instant.now());
//
//        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "close");
//        METERING_CLOSURE.meterDeltaEvent(telemetryContext, "connections", -1);
//        METERING_CLOSURE.meterHistogramMillis(telemetryContext, "connectionDuration");
//        telemetryContext.currentSpan.end();
//    }

//    @Override
//    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
//        trafficOffloader.addDeregisterEvent(Instant.now());
//        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "deregister");
//        super.deregister(ctx, promise);
//    }

//
//    @Override
//    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
//        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "exception");
//        super.exceptionCaught(ctx, cause);
//    }

}
