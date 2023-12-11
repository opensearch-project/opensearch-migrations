package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler<T> extends LoggingHttpRequestHandler<T> {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(@NonNull IChannelConnectionCaptureSerializer<T> trafficOffloader,
                                                          @NonNull RequestCapturePredicate requestCapturePredicate,
                                                          @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock) {
        super(trafficOffloader, requestCapturePredicate);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg,
                                                       boolean shouldCapture, HttpRequest httpRequest)
            throws Exception {
        if (shouldCapture && shouldBlockPredicate.test(httpRequest)) {
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                if (t != null) {
                    // This is a spot where we would benefit from having a behavioral policy that different users
                    // could set as needed. Some users may be fine with just logging a failed offloading of a request
                    // where other users may want to stop entirely. JIRA here: https://opensearch.atlassian.net/browse/MIGRATIONS-1276
                    log.atWarn().setCause(t).setMessage("Got error").log();
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
            super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        }
    }
}
