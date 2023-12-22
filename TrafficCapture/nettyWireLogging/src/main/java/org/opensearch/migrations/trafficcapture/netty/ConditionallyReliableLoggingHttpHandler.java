package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.HttpMessageContext;

import java.io.IOException;
import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpHandler<T> extends LoggingHttpHandler<T> {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpHandler(@NonNull IInstrumentConstructor contextConstructor,
                                                   @NonNull String nodeId, String connectionId,
                                                   @NonNull IConnectionCaptureFactory<T> trafficOffloaderFactory,
                                                   @NonNull RequestCapturePredicate requestCapturePredicate,
                                                   @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock)
    throws IOException {
        super(contextConstructor, nodeId, connectionId, trafficOffloaderFactory, requestCapturePredicate);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg,
                                                       boolean shouldCapture, HttpRequest httpRequest)
            throws Exception {
        if (shouldCapture && shouldBlockPredicate.test(httpRequest)) {
            METERING_CLOSURE.meterIncrementEvent(messageContext, "blockingRequestUntilFlush");
            rotateNextMessageContext(HttpMessageContext.HttpTransactionState.INTERNALLY_BLOCKED);
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                log.atInfo().setMessage(()->"Done flushing").log();
                METERING_CLOSURE.meterIncrementEvent(messageContext,
                        t != null ? "blockedFlushFailure" : "blockedFlushSuccess");
                METERING_CLOSURE.meterHistogramMicros(messageContext,
                        t==null ? "blockedFlushFailure_micro" : "stream_flush_failure_micro");
                messageContext.endSpan();

                if (t != null) {
                    // This is a spot where we would benefit from having a behavioral policy that different users
                    // could set as needed. Some users may be fine with just logging a failed offloading of a request
                    // where other users may want to stop entirely. JIRA here: https://opensearch.atlassian.net/browse/MIGRATIONS-1276
                    log.atWarn().setCause(t).setMessage("Dropping request - Got error").log();
                    ReferenceCountUtil.release(msg);
                } else {
                    try {
                        super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                }
            });
        } else {
            METERING_CLOSURE.meterIncrementEvent(messageContext, "nonBlockingRequest");
            // TODO - log capturing vs non-capturing too
            super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        }
    }
}
