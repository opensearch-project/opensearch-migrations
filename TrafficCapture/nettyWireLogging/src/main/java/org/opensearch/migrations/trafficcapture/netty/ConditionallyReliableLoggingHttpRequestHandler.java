package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.HttpMessageContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler<T> extends LoggingHttpRequestHandler<T> {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(String nodeId, String connectionId,
                                                          IConnectionCaptureFactory<T> trafficOffloaderFactory,
                                                          Predicate<HttpRequest> headerPredicateForWhenToBlock)
    throws IOException {
        super(nodeId, connectionId, trafficOffloaderFactory);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest)
            throws Exception {
        if (shouldBlockPredicate.test(httpRequest)) {
            METERING_CLOSURE.meterIncrementEvent(messageContext, "blockingRequestUntilFlush");
            rotateNextMessageContext(HttpMessageContext.HttpTransactionState.INTERNALLY_BLOCKED);

            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                log.atInfo().setMessage(()->"Done flushing").log();
                METERING_CLOSURE.meterIncrementEvent(messageContext,
                        t != null ? "blockedFlushFailure" : "blockedFlushSuccess");
                METERING_CLOSURE.meterHistogramMicros(messageContext,
                        t==null ? "blockedFlushFailure_micro" : "stream_flush_failure_micro");
                messageContext.getCurrentSpan().end();

                if (t != null) {
                    // This is a spot where we would benefit from having a behavioral policy that different users
                    // could set as needed. Some users may be fine with just logging a failed offloading of a request
                    // where other users may want to stop entirely. JIRA here: https://opensearch.atlassian.net/browse/MIGRATIONS-1276
                    log.warn("Dropping request - Got error: " + t.getMessage());
                    ReferenceCountUtil.release(msg);
                } else {
                    try {
                        super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                }
            });
        } else {
            METERING_CLOSURE.meterIncrementEvent(messageContext, "nonBlockingRequest");
            super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        }
    }
}
