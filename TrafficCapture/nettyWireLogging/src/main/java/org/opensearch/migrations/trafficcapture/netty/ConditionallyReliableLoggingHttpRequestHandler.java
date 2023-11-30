package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.context.ContextKey;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.time.Instant;
import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler<T> extends LoggingHttpRequestHandler<T> {
    private ContextKey<Instant> START_FLUSH_KEY = ContextKey.named("startTime");
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(ConnectionContext incomingContext,
                                                          IChannelConnectionCaptureSerializer<T> trafficOffloader,
                                                          Predicate<HttpRequest> headerPredicateForWhenToBlock) {
        super(incomingContext, trafficOffloader);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest)
            throws Exception {
        if (shouldBlockPredicate.test(httpRequest)) {
            METERING_CLOSURE.meterIncrementEvent(connectionContext, "blockingRequestUntilFlush");
            var flushContext = new ConnectionContext(connectionContext,
                    METERING_CLOSURE.makeSpanContinuation("blockedForFlush"));

            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                log.atInfo().setMessage(()->"Done flushing").log();
                METERING_CLOSURE.meterIncrementEvent(flushContext,
                        t != null ? "blockedFlushFailure" : "blockedFlushSuccess");
                METERING_CLOSURE.meterHistogramMicros(flushContext,
                        t==null ? "blockedFlushFailure_micro" : "stream_flush_failure_micro");
                flushContext.currentSpan.end();

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
            METERING_CLOSURE.meterIncrementEvent(connectionContext, "nonBlockingRequest");
            super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        }
    }
}
