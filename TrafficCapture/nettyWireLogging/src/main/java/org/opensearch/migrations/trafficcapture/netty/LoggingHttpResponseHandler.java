package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
public class LoggingHttpResponseHandler<T> extends ChannelOutboundHandlerAdapter {
    public static final String TELEMETRY_SCOPE_NAME = "LoggingHttpOutboundHandler";
    public static final Optional<MetricsLogger.SimpleMeteringClosure> METERING_CLOSURE_OP =
            Optional.of(new MetricsLogger.SimpleMeteringClosure(TELEMETRY_SCOPE_NAME));
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpResponseHandler");

    private final IChannelConnectionCaptureSerializer<T> trafficOffloader;
    private Context telemetryContext;
    private Instant connectTime;

    public LoggingHttpResponseHandler(Context incomingContext,
                                      IChannelConnectionCaptureSerializer<T> trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
        this.telemetryContext = incomingContext;
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addBindEvent(Instant.now(), localAddress);
        METERING_CLOSURE_OP.ifPresent(m->m.meterIncrementEvent(telemetryContext, "bind"));
        super.bind(ctx, localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addConnectEvent(Instant.now(), remoteAddress, localAddress);

        METERING_CLOSURE_OP.ifPresent(m->{
            var span = GlobalOpenTelemetry.get().getTracer(TELEMETRY_SCOPE_NAME)
                    .spanBuilder("backendConnection").startSpan();
            telemetryContext = telemetryContext.with(span);
            connectTime = Instant.now();

            m.meterIncrementEvent(telemetryContext, "connect");
            m.meterDeltaEvent(telemetryContext, "connections", 1);
        });

        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDisconnectEvent(Instant.now());
        METERING_CLOSURE_OP.ifPresent(m->m.meterIncrementEvent(telemetryContext, "disconnect"));
        super.disconnect(ctx, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());

        METERING_CLOSURE_OP.ifPresent(m-> {
            m.meterIncrementEvent(telemetryContext, "close");
            m.meterDeltaEvent(telemetryContext, "connections", -1);
            m.meterHistogramMillis(telemetryContext, "connectionDuration",
                    Duration.between(connectTime, Instant.now()));
            Span.fromContext(telemetryContext).end();
        });
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDeregisterEvent(Instant.now());
        METERING_CLOSURE_OP.ifPresent(m->m.meterIncrementEvent(telemetryContext, "deregister"));
        super.deregister(ctx, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        var bb = (ByteBuf) msg;
        trafficOffloader.addWriteEvent(Instant.now(), bb);
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_RESPONSE_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
        METERING_CLOSURE_OP.ifPresent(m->{
            m.meterIncrementEvent(telemetryContext, "write");
            m.meterIncrementEvent(telemetryContext, "writeBytes", bb.readableBytes());
        });
        super.write(ctx, msg, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        flush(ctx);
        METERING_CLOSURE_OP.ifPresent(m->m.meterIncrementEvent(telemetryContext, "removed"));
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        METERING_CLOSURE_OP.ifPresent(m->m.meterIncrementEvent(telemetryContext, "exception"));
        super.exceptionCaught(ctx, cause);
    }

}
