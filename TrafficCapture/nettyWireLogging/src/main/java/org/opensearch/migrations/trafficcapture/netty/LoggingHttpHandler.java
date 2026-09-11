package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMessageDecoderResult;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingHttpHandler<T> extends ChannelDuplexHandler {
    public static final Duration DEFAULT_MAXIMUM_CONNECTION_DURATION = Duration.ofMinutes(60);

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
        enum LimitViolation {
            HEADER_BYTES,
            TOTAL_BYTES
        }

        private final PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve;
        private final CaptureState captureState;
        private final long maximumTotalRequestBytes;
        private long currentRequestBytes;
        private long completedRequestCount;
        private LimitViolation limitViolation;

        public SimpleHttpRequestDecoder(
            @NonNull PassThruHttpHeaders.HttpHeadersToPreserve headersToPreserve,
            CaptureState captureState,
            @NonNull IncompleteRequestLimits incompleteRequestLimits
        ) {
            super(
                new HttpDecoderConfig()
                    .setMaxHeaderSize(Math.toIntExact(incompleteRequestLimits.maximumHeaderBytes()))
            );
            this.headersToPreserve = headersToPreserve;
            this.captureState = captureState;
            this.maximumTotalRequestBytes = incompleteRequestLimits.maximumTotalBytes();
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

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            if (limitViolation != null) {
                buffer.skipBytes(buffer.readableBytes());
                return;
            }

            var readerIndexBeforeDecode = buffer.readerIndex();
            var outputCountBeforeDecode = out.size();
            super.decode(ctx, buffer, out);
            currentRequestBytes += buffer.readerIndex() - readerIndexBeforeDecode;

            for (int i = outputCountBeforeDecode; i < out.size(); ++i) {
                var decoded = out.get(i);
                if (decoded instanceof HttpRequest request
                    && request.decoderResult().isFailure()
                    && request.decoderResult().cause() instanceof TooLongHttpHeaderException) {
                    limitViolation = LimitViolation.HEADER_BYTES;
                }
            }

            if (limitViolation == null && currentRequestBytes > maximumTotalRequestBytes) {
                limitViolation = LimitViolation.TOTAL_BYTES;
            }

            for (int i = outputCountBeforeDecode; i < out.size(); ++i) {
                if (out.get(i) instanceof LastHttpContent) {
                    ++completedRequestCount;
                    currentRequestBytes = 0;
                }
            }
        }

        boolean isRequestInProgress() {
            return currentRequestBytes > 0;
        }

        long getCompletedRequestCount() {
            return completedRequestCount;
        }

        LimitViolation getLimitViolation() {
            return limitViolation;
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
    private final IncompleteRequestLimits incompleteRequestLimits;
    protected final CaptureProcessState captureProcessState;
    private final Duration maximumConnectionDuration;
    private CompletableFuture<T> captureCloseFuture;
    private ScheduledFuture<?> connectionDeadline;
    private ScheduledFuture<?> requestAssemblyDeadline;
    private boolean contextsClosed;

    public LoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        String nodeId,
        String channelKey,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate httpHeadersCapturePredicate
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            channelKey,
            trafficOffloaderFactory,
            httpHeadersCapturePredicate,
            IncompleteRequestLimits.DEFAULT,
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
        );
    }

    public LoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        String nodeId,
        String channelKey,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate httpHeadersCapturePredicate,
        @NonNull Duration maximumRequestAssemblyDuration
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            channelKey,
            trafficOffloaderFactory,
            httpHeadersCapturePredicate,
            new IncompleteRequestLimits(
                maximumRequestAssemblyDuration,
                IncompleteRequestLimits.DEFAULT_MAXIMUM_HEADER_BYTES,
                IncompleteRequestLimits.DEFAULT_MAXIMUM_TOTAL_BYTES
            ),
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
        );
    }

    public LoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        String nodeId,
        String channelKey,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate httpHeadersCapturePredicate,
        @NonNull IncompleteRequestLimits incompleteRequestLimits
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            channelKey,
            trafficOffloaderFactory,
            httpHeadersCapturePredicate,
            incompleteRequestLimits,
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
        );
    }

    public LoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        String nodeId,
        String channelKey,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate httpHeadersCapturePredicate,
        @NonNull IncompleteRequestLimits incompleteRequestLimits,
        @NonNull Duration maximumConnectionDuration,
        @NonNull CaptureProcessState captureProcessState
    ) throws IOException {
        var parentContext = rootContext.createConnectionContext(channelKey, nodeId);
        this.messageContext = parentContext.createInitialRequestContext();
        this.incompleteRequestLimits = incompleteRequestLimits;
        if (maximumConnectionDuration.isZero() || maximumConnectionDuration.isNegative()) {
            throw new IllegalArgumentException("maximumConnectionDuration must be positive");
        }
        this.maximumConnectionDuration = maximumConnectionDuration;
        this.captureProcessState = captureProcessState;

        this.trafficOffloader = trafficOffloaderFactory.createOffloader(parentContext);
        var captureState = new CaptureState();
        httpDecoderChannel = new EmbeddedChannel(
            new SimpleHttpRequestDecoder(
                httpHeadersCapturePredicate.getHeadersRequiredForMatcher(),
                captureState,
                incompleteRequestLimits
            ),
            new SimpleDecodedHttpRequestHandler(httpHeadersCapturePredicate, captureState)
        );
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        connectionDeadline = ctx.executor().schedule(
            () -> {
                log.atWarn()
                    .setMessage("Closing connection because it exceeded the maximum connection duration")
                    .log();
                ctx.close();
            },
            maximumConnectionDuration.toNanos(),
            TimeUnit.NANOSECONDS
        );
        super.handlerAdded(ctx);
    }

    private IWireCaptureContexts.ICapturingConnectionContext getConnectionContext() {
        return messageContext.getLogicalEnclosingScope();
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        try {
            getConnectionContext().onUnregistered();
        } finally {
            closeCaptureOnce(Instant.now()).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.atWarn()
                        .setCause(throwable)
                        .setMessage("Unable to finalize capture during channel teardown")
                        .log();
                }
            });
            super.channelUnregistered(ctx);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            if (ctx.channel().isOpen()) {
                ctx.close();
            }
            closeContextsOnce();
        } finally {
            closeCaptureOnce(Instant.now()).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.atWarn()
                        .setCause(throwable)
                        .setMessage("Unable to finalize capture during handler removal")
                        .log();
                }
            });
            super.handlerRemoved(ctx);
        }
    }

    private synchronized CompletableFuture<T> closeCaptureOnce(Instant timestamp) {
        if (captureCloseFuture != null) {
            return captureCloseFuture;
        }
        cancelConnectionDeadline();
        cancelRequestAssemblyDeadline();
        if (!captureProcessState.shouldCapture()) {
            captureCloseFuture = CompletableFuture.completedFuture(null);
            return captureCloseFuture;
        }
        try {
            trafficOffloader.addCloseEvent(timestamp);
            captureCloseFuture = trafficOffloader.flushCommitAndResetStream(true);
        } catch (Throwable t) {
            captureCloseFuture = CompletableFuture.failedFuture(t);
        }
        return captureCloseFuture;
    }

    private void cancelConnectionDeadline() {
        if (connectionDeadline != null) {
            connectionDeadline.cancel(false);
            connectionDeadline = null;
        }
    }

    private synchronized void closeContextsOnce() {
        if (contextsClosed) {
            return;
        }
        contextsClosed = true;
        getConnectionContext().onRemoved();
        messageContext.close();
        messageContext.getLogicalEnclosingScope().close();
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
        var requestDecoder = (SimpleHttpRequestDecoder) httpDecoderChannel.pipeline().first();
        var bb = ((ByteBuf) msg);
        var requestWasInProgress = requestDecoder.isRequestInProgress();
        var completedRequestCountBeforeRead = requestDecoder.getCompletedRequestCount();
        if (!requestWasInProgress && bb.isReadable()) {
            startRequestAssemblyDeadline(ctx);
        }
        httpDecoderChannel.writeInbound(bb.retainedDuplicate()); // the ByteBuf is consumed/release by this method
        updateRequestAssemblyDeadline(
            ctx,
            requestDecoder,
            requestWasInProgress,
            completedRequestCountBeforeRead
        );

        var captureState = requestParsingHandler.captureState;
        var processCaptureEnabled = captureProcessState.shouldCapture();
        var shouldCapture = processCaptureEnabled && captureState.shouldCapture();
        if (shouldCapture) {
            captureState.liveReadObservationsInOffloader = true;
            trafficOffloader.addReadEvent(timestamp, bb);
        } else if (captureState.liveReadObservationsInOffloader) {
            requestContext.onCaptureSuppressed();
            if (processCaptureEnabled) {
                trafficOffloader.cancelCaptureForCurrentRequest(timestamp);
            }
            captureState.liveReadObservationsInOffloader = false;
        }

        requestContext.onBytesRead(bb.readableBytes());

        if (requestDecoder.getLimitViolation() != null) {
            log.atWarn()
                .setMessage("Closing connection because an incomplete request exceeded its {} limit")
                .addArgument(requestDecoder.getLimitViolation())
                .log();
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

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
            }
            channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void updateRequestAssemblyDeadline(
        ChannelHandlerContext ctx,
        SimpleHttpRequestDecoder requestDecoder,
        boolean requestWasInProgress,
        long completedRequestCountBeforeRead
    ) {
        if (requestDecoder.getLimitViolation() != null || !requestDecoder.isRequestInProgress()) {
            cancelRequestAssemblyDeadline();
            return;
        }

        if (requestWasInProgress
            && requestDecoder.getCompletedRequestCount() > completedRequestCountBeforeRead) {
            cancelRequestAssemblyDeadline();
            startRequestAssemblyDeadline(ctx);
        } else if (requestAssemblyDeadline == null) {
            startRequestAssemblyDeadline(ctx);
        }
    }

    private void startRequestAssemblyDeadline(ChannelHandlerContext ctx) {
        if (incompleteRequestLimits.maximumAssemblyDuration().isZero() || requestAssemblyDeadline != null) {
            return;
        }
        requestAssemblyDeadline = ctx.executor().schedule(
            () -> {
                log.atWarn()
                    .setMessage("Closing connection because an incomplete request exceeded its assembly duration")
                    .log();
                ctx.close();
            },
            incompleteRequestLimits.maximumAssemblyDuration().toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private void cancelRequestAssemblyDeadline() {
        if (requestAssemblyDeadline != null) {
            requestAssemblyDeadline.cancel(false);
            requestAssemblyDeadline = null;
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
        if (captureProcessState.shouldCapture()
            && getHandlerThatHoldsParsedHttpRequest().captureState.shouldCapture()) {
            trafficOffloader.addWriteEvent(Instant.now(), bb);
        }
        responseContext.onBytesWritten(bb.readableBytes());

        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        try {
            if (captureProcessState.shouldCapture()) {
                trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
            }
            messageContext.addCaughtException(cause);
            httpDecoderChannel.close();
            super.exceptionCaught(ctx, cause);
        } finally {
            /*
             * ConnectionExceptionObservation is diagnostic only. Explicitly close the real
             * channel so normal channel teardown emits the terminal CloseObservation.
             */
            ctx.close();
        }
    }

}
