package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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
import lombok.Getter;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.HttpMessageContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.io.IOException;
import java.time.Instant;

@Slf4j
public class LoggingHttpRequestHandler<T> extends ChannelDuplexHandler {
    public static final String TELEMETRY_SCOPE_NAME = "CapturingHttpHandler";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpRequestHandler");
    public static final String GATHERING_REQUEST = "gatheringRequest";
    public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
    public static final String GATHERING_RESPONSE = "gatheringResponse";
    public static final String BLOCKED = "blocked";

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

    protected HttpMessageContext messageContext;

    public LoggingHttpRequestHandler(String nodeId, String channelKey,
                                     IConnectionCaptureFactory<T> trafficOffloaderFactory)
    throws IOException {
        var parentContext = new ConnectionContext(channelKey, nodeId,
                METERING_CLOSURE.makeSpanContinuation("connectionLifetime", null));

        this.messageContext = new HttpMessageContext(parentContext, 0, HttpMessageContext.HttpTransactionState.REQUEST,
                METERING_CLOSURE.makeSpanContinuation(GATHERING_REQUEST));
        METERING_CLOSURE.meterIncrementEvent(messageContext, "requestStarted");

        this.trafficOffloader = trafficOffloaderFactory.createOffloader(parentContext, channelKey);
        httpDecoderChannel = new EmbeddedChannel(
                new SimpleHttpRequestDecoder(),
                new SimpleDecodedHttpRequestHandler());
    }

    static String getSpanLabelForState(HttpMessageContext.HttpTransactionState state) {
        switch (state) {
            case REQUEST:
                return GATHERING_REQUEST;
            case INTERNALLY_BLOCKED:
                return BLOCKED;
            case WAITING:
                return WAITING_FOR_RESPONSE;
            case RESPONSE:
                return GATHERING_RESPONSE;
            default:
                throw new IllegalStateException("Unknown enum value: "+state);
        }
    }

    protected void rotateNextMessageContext(HttpMessageContext.HttpTransactionState nextState) {
        messageContext = new HttpMessageContext(messageContext.getEnclosingScope(),
                (nextState== HttpMessageContext.HttpTransactionState.REQUEST ? 1 : 0)
                        + messageContext.getSourceRequestIndex(),
                nextState,
                METERING_CLOSURE.makeSpanContinuation(getSpanLabelForState(nextState)));
    }

    private HttpProcessedState parseHttpMessageParts(ByteBuf msg)  {
        httpDecoderChannel.writeInbound(msg); // Consume this outright, up to the caller to know what else to do
        var state = getHandlerThatHoldsParsedHttpRequest().isDone ?
                HttpProcessedState.FULL_MESSAGE :
                HttpProcessedState.ONGOING;
        METERING_CLOSURE.meterIncrementEvent(messageContext,
                state == HttpProcessedState.FULL_MESSAGE ? "requestFullyParsed" : "requestPartiallyParsed");
        return state;
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        METERING_CLOSURE.meterIncrementEvent(messageContext, "unregistered");
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
        METERING_CLOSURE.meterIncrementEvent(messageContext, "handlerRemoved");
        messageContext.getCurrentSpan().end();
        messageContext.getEnclosingScope().currentSpan.end();

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
        rotateNextMessageContext(HttpMessageContext.HttpTransactionState.WAITING);
        super.channelRead(ctx, msg);
        METERING_CLOSURE.meterIncrementEvent(messageContext, "requestReceived");

        metricsLogger.atSuccess(MetricsEvent.RECEIVED_FULL_HTTP_REQUEST)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText())
                .setAttribute(MetricsAttributeKey.HTTP_METHOD, httpRequest.method().toString())
                .setAttribute(MetricsAttributeKey.HTTP_ENDPOINT, httpRequest.uri()).emit();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (messageContext.getState() == HttpMessageContext.HttpTransactionState.RESPONSE) {
            messageContext.getCurrentSpan().end();
            rotateNextMessageContext(HttpMessageContext.HttpTransactionState.REQUEST);
        }
        var timestamp = Instant.now();
        HttpProcessedState httpProcessedState;
        {
            var bb = ((ByteBuf) msg).retainedDuplicate();
            trafficOffloader.addReadEvent(timestamp, bb);
            METERING_CLOSURE.meterIncrementEvent(messageContext, "read");
            METERING_CLOSURE.meterIncrementEvent(messageContext, "readBytes", bb.readableBytes());

            metricsLogger.atSuccess(MetricsEvent.RECEIVED_REQUEST_COMPONENT)
                    .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();

            httpProcessedState = parseHttpMessageParts(bb); // bb is consumed/release by this method
        }
        if (httpProcessedState == HttpProcessedState.FULL_MESSAGE) {
            messageContext.getCurrentSpan().end();
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
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (messageContext.getState() != HttpMessageContext.HttpTransactionState.RESPONSE) {
            messageContext.getCurrentSpan().end();
            rotateNextMessageContext(HttpMessageContext.HttpTransactionState.RESPONSE);
        }
        var bb = (ByteBuf) msg;
        trafficOffloader.addWriteEvent(Instant.now(), bb);
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_RESPONSE_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
        METERING_CLOSURE.meterIncrementEvent(messageContext, "write");
        METERING_CLOSURE.meterIncrementEvent(messageContext, "writeBytes", bb.readableBytes());

        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        METERING_CLOSURE.meterIncrementEvent(messageContext, "exception");
        httpDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
