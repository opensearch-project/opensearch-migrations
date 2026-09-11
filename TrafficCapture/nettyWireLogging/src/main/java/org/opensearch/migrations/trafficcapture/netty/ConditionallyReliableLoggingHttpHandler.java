package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Predicate;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;
import org.opensearch.migrations.trafficcapture.netty.tracing.IWireCaptureContexts;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConditionallyReliableLoggingHttpHandler<T> extends LoggingHttpHandler<T> {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        @NonNull String nodeId,
        String connectionId,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock,
        @NonNull CaptureProcessState captureProcessState
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            connectionId,
            trafficOffloaderFactory,
            requestCapturePredicate,
            headerPredicateForWhenToBlock,
            IncompleteRequestLimits.DEFAULT,
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            captureProcessState
        );
    }

    public ConditionallyReliableLoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        @NonNull String nodeId,
        String connectionId,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock,
        @NonNull Duration maximumRequestAssemblyDuration,
        @NonNull CaptureProcessState captureProcessState
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            connectionId,
            trafficOffloaderFactory,
            requestCapturePredicate,
            headerPredicateForWhenToBlock,
            new IncompleteRequestLimits(
                maximumRequestAssemblyDuration,
                IncompleteRequestLimits.DEFAULT_MAXIMUM_HEADER_BYTES,
                IncompleteRequestLimits.DEFAULT_MAXIMUM_TOTAL_BYTES
            ),
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            captureProcessState
        );
    }

    public ConditionallyReliableLoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        @NonNull String nodeId,
        String connectionId,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock,
        @NonNull IncompleteRequestLimits incompleteRequestLimits,
        @NonNull CaptureProcessState captureProcessState
    ) throws IOException {
        this(
            rootContext,
            nodeId,
            connectionId,
            trafficOffloaderFactory,
            requestCapturePredicate,
            headerPredicateForWhenToBlock,
            incompleteRequestLimits,
            DEFAULT_MAXIMUM_CONNECTION_DURATION,
            captureProcessState
        );
    }

    public ConditionallyReliableLoggingHttpHandler(
        @NonNull IRootWireLoggingContext rootContext,
        @NonNull String nodeId,
        String connectionId,
        @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock,
        @NonNull IncompleteRequestLimits incompleteRequestLimits,
        @NonNull Duration maximumConnectionDuration,
        @NonNull CaptureProcessState captureProcessState
    ) throws IOException {
        super(
            rootContext,
            nodeId,
            connectionId,
            trafficOffloaderFactory,
            requestCapturePredicate,
            incompleteRequestLimits,
            maximumConnectionDuration,
            captureProcessState
        );
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(
        ChannelHandlerContext ctx,
        Object msg,
        boolean shouldCapture,
        HttpRequest httpRequest
    ) throws Exception {
        if (shouldCapture && shouldBlockPredicate.test(httpRequest)) {
            ((IWireCaptureContexts.IRequestContext) messageContext).onBlockingRequest();
            messageContext = messageContext.createBlockingContext();
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, failure) ->
                runOnEventLoop(
                    ctx,
                    () -> finishBlockedRequest(ctx, msg, shouldCapture, httpRequest, failure),
                    msg
                )
            );
        } else {
            assert messageContext instanceof IWireCaptureContexts.IRequestContext;
            super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        }
    }

    private void finishBlockedRequest(
        ChannelHandlerContext ctx,
        Object msg,
        boolean shouldCapture,
        HttpRequest httpRequest,
        Throwable failure
    ) {
        if (failure != null) {
            messageContext.addCaughtException(failure);
            var resultingState = captureProcessState.requiredCaptureFailed(failure);
            if (resultingState == CaptureProcessState.State.TERMINATING) {
                log.atError()
                    .setCause(failure)
                    .setMessage("Capture failed; refusing to forward the request before process termination")
                    .log();
                ReferenceCountUtil.release(msg);
                ctx.close();
                return;
            }
            log.atError()
                .setCause(failure)
                .setMessage("Capture failed; forwarding in irreversible process-wide pass-through mode")
                .log();
        }
        try {
            super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        } catch (Exception e) {
            ReferenceCountUtil.release(msg);
            ctx.fireExceptionCaught(e);
            ctx.close();
        }
    }

    private static void runOnEventLoop(
        ChannelHandlerContext ctx,
        Runnable continuation,
        Object retainedMessage
    ) {
        if (ctx.executor().inEventLoop()) {
            continuation.run();
            return;
        }
        try {
            ctx.executor().execute(continuation);
        } catch (RuntimeException e) {
            ReferenceCountUtil.release(retainedMessage);
            ctx.fireExceptionCaught(e);
            ctx.close();
        }
    }
}
