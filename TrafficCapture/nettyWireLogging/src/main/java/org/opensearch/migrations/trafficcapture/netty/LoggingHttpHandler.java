package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.time.Instant;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;
import org.opensearch.migrations.trafficcapture.netty.tracing.IWireCaptureContexts;

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
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingHttpHandler<T> extends ChannelDuplexHandler {

    static class CaptureIgnoreState {
        static final byte CAPTURE = 0;
        static final byte IGNORE_REQUEST = 1;
        static final byte IGNORE_RESPONSE = 2;

        private CaptureIgnoreState() {}
    }

    static class CaptureState {
        byte captureIgnoreState = CaptureIgnoreState.CAPTURE;
        boolean liveReadObservationsInOffloader = false;

        boolean shouldCapture() {
            return captureIgnoreState == CaptureIgnoreState.CAPTURE;
        }

        public void setShouldCaptureForRequest(boolean b) {
            captureIgnoreState = b ? CaptureIgnoreState.CAPTURE : CaptureIgnoreState.IGNORE_REQUEST;
        }

        public void advanceStateModelIntoResponseGather() {
            if (CaptureIgnoreState.CAPTURE != captureIgnoreState) {
                captureIgnoreState = CaptureIgnoreState.IGNORE_RESPONSE;
            }
        }
    }

    static class SimpleHttpRequestDecoder extends HttpRequestDecoder {
        private final PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve;
        private final CaptureState captureState;

        public SimpleHttpRequestDecoder(
            @NonNull PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve,
            CaptureState captureState
        ) {
            this.headersToPreserve = headersToPreserve;
            this.captureState = captureState;
        }

        /**
         * Override this so that the HttpHeaders object can be a cheaper one.  PassThruHeaders
         * only stores a handful of headers that are required for parsing the payload portion
         * of an HTTP Message.
         */
        @Override
        public HttpMessage createMessage(String[] initialLine) throws Exception {
            return new DefaultHttpRequest(
                HttpVersion.valueOf(initialLine[2]),
                HttpMethod.valueOf(initialLine[0]),
                initialLine[1],
                new PassThruHttpHeaders(headersToPreserve)
            );
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (captureState.captureIgnoreState == CaptureIgnoreState.IGNORE_RESPONSE) {
                captureState.captureIgnoreState = CaptureIgnoreState.CAPTURE;
            }
            super.channelRead(ctx, msg);
        }
    }

    static class SimpleDecodedHttpRequestHandler extends ChannelInboundHandlerAdapter {
        @Getter
        private HttpRequest currentRequest;
        final RequestCapturePredicate requestCapturePredicate;
        boolean haveParsedFullRequest;
        final CaptureState captureState;

        SimpleDecodedHttpRequestHandler(RequestCapturePredicate requestCapturePredicate, CaptureState captureState) {
            this.requestCapturePredicate = requestCapturePredicate;
            this.currentRequest = null;
            this.haveParsedFullRequest = false;
            this.captureState = captureState;
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                currentRequest = (HttpRequest) msg;
                captureState.setShouldCaptureForRequest(
                    RequestCapturePredicate.CaptureDirective.CAPTURE == requestCapturePredicate.apply((HttpRequest) msg)
                );
            } else if (msg instanceof HttpContent) {
                ((HttpContent) msg).release();
                if (msg instanceof LastHttpContent) {
                    haveParsedFullRequest = true;
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        public HttpRequest resetCurrentRequest() {
            this.haveParsedFullRequest = false;
            var old = currentRequest;
            this.currentRequest = null;
            return old;
        }
    }

    protected final IChannelConnectionCaptureSerializer<T> trafficOffloader;

    protected final EmbeddedChannel httpDecoderChannel;

    protected IWireCaptureContexts.IHttpMessageContext messageContext;

    public LoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        String nodeId,
        String channelKey,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate httpHeadersCapturePredicate
    ) throws IOException {
        var parentContext = rootContext.createConnectionContext(channelKey, nodeId);
        this.messageContext = parentContext.createInitialRequestContext();

        this.trafficOffloader = trafficOffloaderFactory.createOffloader(parentContext);
        var captureState = new CaptureState();
        httpDecoderChannel = new EmbeddedChannel(
            new SimpleHttpRequestDecoder(httpHeadersCapturePredicate.getHeadersRequiredForMatcher(), captureState),
            new SimpleDecodedHttpRequestHandler(httpHeadersCapturePredicate, captureState)
        );
    }

    private IWireCaptureContexts.ICapturingConnectionContext getConnectionContext() {
        return messageContext.getLogicalEnclosingScope();
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        getConnectionContext().onUnregistered();
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
        getConnectionContext().onRemoved();
        messageContext.close();
        messageContext.getLogicalEnclosingScope().close();

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

    /**
     * This provides a callback that subclasses can use to override the default behavior of cycling the
     * instrumentation context and continuing to read.  Subclasses may determine if additional processing
     * or triggers should occur before proceeding, given the current context.
     * @param ctx the instrumentation context for this request
     * @param msg the original message, which is likely a ByteBuf, that helped to form the httpRequest
     * @param shouldCapture false if the current request has been determined to be ignorable
     * @param httpRequest the request that has just been fully received (excluding its body)
     */
    protected void channelFinishedReadingAnHttpMessage(
        ChannelHandlerContext ctx,
        Object msg,
        boolean shouldCapture,
        HttpRequest httpRequest
    ) throws Exception {
        messageContext = messageContext.createWaitingForResponseContext();
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
        IWireCaptureContexts.IRequestContext requestContext;
        if (!(messageContext instanceof IWireCaptureContexts.IRequestContext)) {
            messageContext = requestContext = messageContext.createNextRequestContext();
        } else {
            requestContext = (IWireCaptureContexts.IRequestContext) messageContext;
        }

        var timestamp = Instant.now();
        var requestParsingHandler = getHandlerThatHoldsParsedHttpRequest();
        var bb = ((ByteBuf) msg);
        httpDecoderChannel.writeInbound(bb.retainedDuplicate()); // the ByteBuf is consumed/release by this method

        var captureState = requestParsingHandler.captureState;
        var shouldCapture = captureState.shouldCapture();
        if (shouldCapture) {
            captureState.liveReadObservationsInOffloader = true;
            trafficOffloader.addReadEvent(timestamp, bb);
        } else if (captureState.liveReadObservationsInOffloader) {
            requestContext.onCaptureSuppressed();
            trafficOffloader.cancelCaptureForCurrentRequest(timestamp);
            captureState.liveReadObservationsInOffloader = false;
        }

        requestContext.onBytesRead(bb.readableBytes());

        if (requestParsingHandler.haveParsedFullRequest) {
            requestContext.onFullyParsedRequest();
            var httpRequest = requestParsingHandler.resetCurrentRequest();
            captureState.liveReadObservationsInOffloader = false;
            captureState.advanceStateModelIntoResponseGather();

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
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        IWireCaptureContexts.IResponseContext responseContext;
        if (!(messageContext instanceof IWireCaptureContexts.IResponseContext)) {
            messageContext = responseContext = messageContext.createResponseContext();
        } else {
            responseContext = (IWireCaptureContexts.IResponseContext) messageContext;
        }

        var bb = (ByteBuf) msg;
        if (getHandlerThatHoldsParsedHttpRequest().captureState.shouldCapture()) {
            trafficOffloader.addWriteEvent(Instant.now(), bb);
        }
        responseContext.onBytesWritten(bb.readableBytes());

        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        messageContext.addCaughtException(cause);
        httpDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
