package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler extends LoggingHttpRequestHandler {
    private final Predicate<HttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(IChannelConnectionCaptureSerializer trafficOffloader,
                                                          Predicate<HttpRequest> headerPredicateForWhenToBlock) {
        super(trafficOffloader);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest) throws Exception {
        if (shouldBlockPredicate.test(httpRequest)) {
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                if (t != null) {
                    // This is a spot where we would benefit from having a behavioral policy that different users
                    // could set as needed. Some users may be fine with just logging a failed offloading of a request
                    // where other users may want to stop entirely. JIRA here: https://opensearch.atlassian.net/browse/MIGRATIONS-1276
                    log.atWarn().setMessage(()->"Got error: " + t.getMessage());
                }
                try {
                    super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        }
    }
}
