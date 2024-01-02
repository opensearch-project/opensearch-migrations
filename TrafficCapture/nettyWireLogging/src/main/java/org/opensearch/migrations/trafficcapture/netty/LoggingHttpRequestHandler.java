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
import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.time.Instant;

@Slf4j
public class LoggingHttpRequestHandler<T> extends ChannelInboundHandlerAdapter {
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpRequestHandler");

    static class SimpleHttpRequestDecoder extends HttpRequestDecoder {
        private final PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve;

        public SimpleHttpRequestDecoder(@NonNull PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve) {
            this.headersToPreserve = headersToPreserve;
        }

        /**
         * Override this so that the HttpHeaders object can be a cheaper one.  PassThruHeaders
         * only stores a handful of headers that are required for parsing the payload portion
         * of an HTTP Message.
         */
        @Override
        public HttpMessage createMessage(String[] initialLine) throws Exception {
            return new DefaultHttpRequest(HttpVersion.valueOf(initialLine[2]),
                    HttpMethod.valueOf(initialLine[0]), initialLine[1]
                    , new PassThruHttpHeaders(headersToPreserve)
            );
        }
    }

    static class SimpleDecodedHttpRequestHandler extends ChannelInboundHandlerAdapter {
        @Getter
        private HttpRequest currentRequest;
        final RequestCapturePredicate requestCapturePredicate;
        boolean isDone;
        boolean shouldCapture;
        boolean liveReadObservationsInOffloader;

        SimpleDecodedHttpRequestHandler(RequestCapturePredicate requestCapturePredicate) {
            this.requestCapturePredicate = requestCapturePredicate;
            this.currentRequest = null;
            this.isDone = false;
            this.shouldCapture = true;
            liveReadObservationsInOffloader = false;
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                currentRequest = (HttpRequest) msg;
                shouldCapture = RequestCapturePredicate.CaptureDirective.CAPTURE ==
                        requestCapturePredicate.apply((HttpRequest) msg);
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
            this.shouldCapture = true;
            this.isDone = false;
            var old = currentRequest;
            this.currentRequest = null;
            this.liveReadObservationsInOffloader = false;
            return old;
        }
    }

    protected final IChannelConnectionCaptureSerializer<T> trafficOffloader;

    protected final EmbeddedChannel httpDecoderChannel;

    public LoggingHttpRequestHandler(IChannelConnectionCaptureSerializer<T> trafficOffloader,
                                     @NonNull RequestCapturePredicate httpHeadersCapturePredicate) {
        this.trafficOffloader = trafficOffloader;
        httpDecoderChannel = new EmbeddedChannel(
                new SimpleHttpRequestDecoder(httpHeadersCapturePredicate.getHeadersRequiredForMatcher()),
                new SimpleDecodedHttpRequestHandler(httpHeadersCapturePredicate)
        );
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
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

    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, boolean shouldCapture,
                                                       HttpRequest httpRequest) throws Exception {
        super.channelRead(ctx, msg);
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_FULL_HTTP_REQUEST)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText())
                .setAttribute(MetricsAttributeKey.HTTP_METHOD, httpRequest.method().toString())
                .setAttribute(MetricsAttributeKey.HTTP_ENDPOINT, httpRequest.uri()).emit();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        var timestamp = Instant.now();
        var requestParsingHandler = getHandlerThatHoldsParsedHttpRequest();
        var bb = ((ByteBuf) msg);
        httpDecoderChannel.writeInbound(bb.retainedDuplicate()); // the ByteBuf is consumed/release by this method
        var shouldCapture = requestParsingHandler.shouldCapture;
        if (shouldCapture) {
            requestParsingHandler.liveReadObservationsInOffloader = true;
            trafficOffloader.addReadEvent(timestamp, bb);
        } else if (requestParsingHandler.liveReadObservationsInOffloader) {
            trafficOffloader.cancelCaptureForCurrentRequest(timestamp);
            requestParsingHandler.liveReadObservationsInOffloader = false;
        }
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_REQUEST_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();

        if (requestParsingHandler.isDone) {
            var httpRequest = requestParsingHandler.resetCurrentRequest();
            if (shouldCapture) {
                var decoderResultLoose = httpRequest.decoderResult();
                if (decoderResultLoose instanceof HttpMessageDecoderResult) {
                    var decoderResult = (HttpMessageDecoderResult) decoderResultLoose;
                    trafficOffloader.addEndOfFirstLineIndicator(decoderResult.initialLineLength());
                    trafficOffloader.addEndOfHeadersIndicator(decoderResult.headerSize());
                }
                trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
            }
            channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        httpDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
