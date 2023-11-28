package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler<T> extends LoggingHttpRequestHandler<T> {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(Context incomingContext,
                                                          IChannelConnectionCaptureSerializer<T> trafficOffloader,
                                                          Predicate<HttpRequest> headerPredicateForWhenToBlock) {
        super(incomingContext, trafficOffloader);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest)
            throws Exception {
        if (shouldBlockPredicate.test(httpRequest)) {
            Span blockingSpan;
            METERING_CLOSURE.meterIncrementEvent(telemetryContext, "blockingRequestUntilFlush");
            try (var namedOnlyForAutoClose = telemetryContext.makeCurrent()) {
                blockingSpan = GlobalOpenTelemetry.get().getTracer(TELEMETRY_SCOPE_NAME)
                        .spanBuilder("blockedOnFlush").startSpan();
            }
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                blockingSpan.end();
                METERING_CLOSURE.meterIncrementEvent(telemetryContext,
                        t != null ? "blockedFlushFailure" : "blockedFlushSuccess");

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
            METERING_CLOSURE.meterIncrementEvent(telemetryContext, "nonBlockingRequest");
            super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        }
    }
}
