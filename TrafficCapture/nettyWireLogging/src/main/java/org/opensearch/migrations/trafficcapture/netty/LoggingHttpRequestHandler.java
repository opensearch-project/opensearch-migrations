package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMessageDecoderResult;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import lombok.Getter;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.SimpleMeteringClosure;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.time.Instant;

@Slf4j
public class LoggingHttpRequestHandler<T> extends ChannelInboundHandlerAdapter {
    public static final String TELEMETRY_SCOPE_NAME = "LoggingHttpInboundHandler";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpRequestHandler");

    static class SimpleHttpRequestDecoder extends HttpRequestDecoder {
        /**
         * Override this so that the HttpHeaders object can be a cheaper one.  PassThruHeaders
         * only stores a handful of headers that are required for parsing the payload portion
         * of an HTTP Message.
         */
        @Override
        public HttpMessage createMessage(String[] initialLine) throws Exception {
            return new DefaultHttpRequest(HttpVersion.valueOf(initialLine[2]),
                    HttpMethod.valueOf(initialLine[0]), initialLine[1]
                    , new PassThruHttpHeaders()
            );
        }
    }

    static class SimpleDecodedHttpRequestHandler extends ChannelInboundHandlerAdapter {
        @Getter
        private HttpRequest currentRequest;
        boolean isDone;
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                currentRequest = (HttpRequest) msg;
            } else if (msg instanceof HttpContent) {
                ((HttpContent)msg).release();
                if (msg instanceof LastHttpContent) {
                    isDone = true;
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        public HttpRequest resetCurrentRequest() {
            isDone = false;
            var old = currentRequest;
            currentRequest = null;
            return old;
        }
    }

    protected final IChannelConnectionCaptureSerializer<T> trafficOffloader;

    protected final EmbeddedChannel httpDecoderChannel;
    protected final SimpleHttpRequestDecoder requestDecoder;
    protected final Context telemetryContext;
    private final Instant createdTime;


    public LoggingHttpRequestHandler(Context incomingContext, IChannelConnectionCaptureSerializer<T> trafficOffloader) {
        this.createdTime = Instant.now();
        try (var scope = incomingContext.makeCurrent()) {
            var span = METERING_CLOSURE.tracer.spanBuilder("frontendConnection").startSpan();
            telemetryContext = incomingContext.with(span);
            METERING_CLOSURE.meterIncrementEvent(telemetryContext, "requestStarted");
        }

        this.trafficOffloader = trafficOffloader;
        requestDecoder = new SimpleHttpRequestDecoder(); // as a field for easier debugging
        httpDecoderChannel = new EmbeddedChannel(
                requestDecoder,
                new SimpleDecodedHttpRequestHandler()
        );
    }

    private HttpProcessedState parseHttpMessageParts(ByteBuf msg)  {
        httpDecoderChannel.writeInbound(msg); // Consume this outright, up to the caller to know what else to do
        var state = getHandlerThatHoldsParsedHttpRequest().isDone ?
                HttpProcessedState.FULL_MESSAGE :
                HttpProcessedState.ONGOING;
        METERING_CLOSURE.meterIncrementEvent(telemetryContext,
                state == HttpProcessedState.FULL_MESSAGE ? "requestFullyParsed" : "requestPartiallyParsed");
        return state;
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "unregistered");
        trafficOffloader.flushCommitAndResetStream(true).whenComplete((result, t) -> {
            if (t != null) {
                log.warn("Got error: " + t.getMessage());
                ctx.close();
            } else {
                try {
                    super.channelUnregistered(ctx);
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }
        });
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "handlerRemoved");
        Span.fromContext(telemetryContext).end();

        trafficOffloader.flushCommitAndResetStream(true).whenComplete((result, t) -> {
            if (t != null) {
                log.warn("Got error: " + t.getMessage());
            }
            try {
                super.channelUnregistered(ctx);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        });
        super.handlerRemoved(ctx);
    }

    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest) throws Exception {
        super.channelRead(ctx, msg);
        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "requestReceived");

        metricsLogger.atSuccess(MetricsEvent.RECEIVED_FULL_HTTP_REQUEST)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText())
                .setAttribute(MetricsAttributeKey.HTTP_METHOD, httpRequest.method().toString())
                .setAttribute(MetricsAttributeKey.HTTP_ENDPOINT, httpRequest.uri()).emit();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        var timestamp = Instant.now();
        HttpProcessedState httpProcessedState;
        {
            var bb = ((ByteBuf) msg).retainedDuplicate();
            trafficOffloader.addReadEvent(timestamp, bb);
            METERING_CLOSURE.meterIncrementEvent(telemetryContext, "read");
            METERING_CLOSURE.meterIncrementEvent(telemetryContext, "readBytes", bb.readableBytes());

            metricsLogger.atSuccess(MetricsEvent.RECEIVED_REQUEST_COMPONENT)
                    .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();

            httpProcessedState = parseHttpMessageParts(bb); // bb is consumed/release by this method
        }
        if (httpProcessedState == HttpProcessedState.FULL_MESSAGE) {
            var httpRequest = getHandlerThatHoldsParsedHttpRequest().resetCurrentRequest();
            var decoderResultLoose = httpRequest.decoderResult();
            if (decoderResultLoose instanceof HttpMessageDecoderResult) {
                var decoderResult = (HttpMessageDecoderResult) decoderResultLoose;
                trafficOffloader.addEndOfFirstLineIndicator(decoderResult.initialLineLength());
                trafficOffloader.addEndOfHeadersIndicator(decoderResult.headerSize());
            }
            trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
            channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        METERING_CLOSURE.meterIncrementEvent(telemetryContext, "exception");
        httpDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
