package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;

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
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMessageDecoderResult;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
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

    /**
     * Response-side counterpart to {@link SimpleHttpRequestDecoder}. Unlike the request decoder, this
     * needs no capture predicate or header matcher — the capture-or-not decision was already made when
     * the request came in ({@link CaptureState#shouldCapture()}), so this exists purely to detect where
     * a response ends. {@link PassThruHttpHeaders} is reused as-is: the headers it tracks
     * (Content-Length/Transfer-Encoding/Trailer) are what Netty's decoder needs to find that boundary
     * on ANY HTTP message, request or response — nothing about them is request-specific.
     */
    static class SimpleHttpResponseDecoder extends HttpResponseDecoder {
        private final PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve;
        // Plain HttpResponseDecoder has no way to know which HTTP method produced a given
        // response, so on its own it can't tell that a HEAD response carrying Content-Length
        // has no body — it would wait for body bytes that never arrive and then misparse the
        // start of the next response as leftover body, desyncing every response after it on
        // the connection. Netty's own HttpClientCodec solves this exact problem the same way:
        // one method queued per request as it's read, one polled per response as its headers
        // finish parsing (requests and responses are handled one at a time on this connection,
        // never pipelined, so a simple FIFO queue is always in the right order).
        private final Queue<HttpMethod> requestMethodQueue = new ArrayDeque<>();

        public SimpleHttpResponseDecoder(@NonNull PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve) {
            this.headersToPreserve = headersToPreserve;
        }

        void requestMethodParsed(HttpMethod method) {
            requestMethodQueue.add(method);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage msg) {
            var requestMethod = requestMethodQueue.poll();
            return HttpMethod.HEAD.equals(requestMethod) || super.isContentAlwaysEmpty(msg);
        }

        @Override
        public HttpMessage createMessage(String[] initialLine) {
            return new DefaultHttpResponse(
                HttpVersion.valueOf(initialLine[0]),
                HttpResponseStatus.valueOf(Integer.parseInt(initialLine[1]), initialLine[2]),
                new PassThruHttpHeaders(headersToPreserve)
            );
        }
    }

    static class SimpleDecodedHttpResponseHandler extends ChannelInboundHandlerAdapter {
        @Getter
        private HttpResponse currentResponse;
        boolean haveParsedFullResponse;

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpResponse) {
                currentResponse = (HttpResponse) msg;
            } else if (msg instanceof HttpContent) {
                ((HttpContent) msg).release();
                if (msg instanceof LastHttpContent) {
                    haveParsedFullResponse = true;
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        public HttpResponse resetCurrentResponse() {
            this.haveParsedFullResponse = false;
            var old = currentResponse;
            this.currentResponse = null;
            return old;
        }
    }

    protected final IChannelConnectionCaptureSerializer<T> trafficOffloader;

    protected final EmbeddedChannel httpDecoderChannel;
    protected final EmbeddedChannel httpResponseDecoderChannel;

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
        httpResponseDecoderChannel = new EmbeddedChannel(
            new SimpleHttpResponseDecoder(new PassThruHttpHeaders.HttpHeadersToPreserve()),
            new SimpleDecodedHttpResponseHandler()
        );
    }

    private IWireCaptureContexts.ICapturingConnectionContext getConnectionContext() {
        return messageContext.getLogicalEnclosingScope();
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    private SimpleDecodedHttpResponseHandler getHandlerThatHoldsParsedHttpResponse() {
        return (SimpleDecodedHttpResponseHandler) httpResponseDecoderChannel.pipeline().last();
    }

    private SimpleHttpResponseDecoder getResponseDecoder() {
        return (SimpleHttpResponseDecoder) httpResponseDecoderChannel.pipeline().first();
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
                } else {
                    log.atWarn().setMessage("HttpRequest decoder result was not an HttpMessageDecoderResult "
                        + "(was {}). EOM will have -1 for firstLineByteLength and headersByteLength. "
                        + "This may indicate a missing header in PassThruHttpHeaders.")
                        .addArgument(() -> decoderResultLoose.getClass().getName())
                        .log();
                }
                trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
                // One entry per request that will actually be fed into httpResponseDecoderChannel
                // (write() below only writes response bytes into it when shouldCapture is true for
                // this same request/response cycle) — see SimpleHttpResponseDecoder's queue comment.
                getResponseDecoder().requestMethodParsed(httpRequest.method());
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

        var timestamp = Instant.now();
        var bb = (ByteBuf) msg;
        var shouldCapture = getHandlerThatHoldsParsedHttpRequest().captureState.shouldCapture();
        if (shouldCapture) {
            trafficOffloader.addWriteEvent(timestamp, bb);

            // Without this, the accumulator on the far end only learns a response is complete
            // via connection close, connection reuse (next request read), or an expiry timeout
            // — all proxies for "done", never a real signal — so a response sent over a
            // still-open keep-alive connection sits unfinalized until one of those eventually
            // fires. This mirrors channelRead()'s embedded-decoder pattern above so a response
            // gets the same deterministic, immediate end-of-message signal a request already
            // does. SimpleHttpResponseDecoder's request-method queue (fed above, when the
            // request finished parsing) is what lets it correctly treat a HEAD response as
            // bodyless despite a declared Content-Length, same as HttpClientCodec does.
            var responseParsingHandler = getHandlerThatHoldsParsedHttpResponse();
            httpResponseDecoderChannel.writeInbound(bb.retainedDuplicate()); // consumed/released by this method
            if (responseParsingHandler.haveParsedFullResponse) {
                var httpResponse = responseParsingHandler.resetCurrentResponse();
                var decoderResultLoose = httpResponse.decoderResult();
                if (decoderResultLoose instanceof HttpMessageDecoderResult) {
                    var decoderResult = (HttpMessageDecoderResult) decoderResultLoose;
                    trafficOffloader.addEndOfFirstLineIndicator(decoderResult.initialLineLength());
                    trafficOffloader.addEndOfHeadersIndicator(decoderResult.headerSize());
                } else {
                    log.atWarn().setMessage("HttpResponse decoder result was not an HttpMessageDecoderResult "
                        + "(was {}). EOM will have -1 for firstLineByteLength and headersByteLength. "
                        + "This may indicate a missing header in PassThruHttpHeaders.")
                        .addArgument(() -> decoderResultLoose.getClass().getName())
                        .log();
                }
                trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
                // commitEndOfHttpMessageIndicator() only appends the EOM marker to the current
                // in-memory buffer — it does NOT push anything to Kafka. Without an explicit
                // flush here, a response sits buffered until some UNRELATED later trigger comes
                // along (the request side gets exactly this treatment already, via
                // ConditionallyReliableLoggingHttpHandler's blocking flushCommitAndResetStream()
                // before forwarding to the backend). On a keep-alive connection with no next
                // request imminent, that could be connection teardown minutes later — long past
                // the accumulator's own observedPacketConnectionTimeout, so it gives up and
                // finalizes the tuple with no response, and the real data arrives too late for
                // anything to be listening for it. A response needs the same not-just-buffered
                // guarantee a request already gets.
                trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                    if (t != null) {
                        log.atWarn().setCause(t)
                            .setMessage("Error flushing captured response; response data may be lost")
                            .log();
                    }
                });
            }
        }
        responseContext.onBytesWritten(bb.readableBytes());

        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        messageContext.addCaughtException(cause);
        httpDecoderChannel.close();
        httpResponseDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
