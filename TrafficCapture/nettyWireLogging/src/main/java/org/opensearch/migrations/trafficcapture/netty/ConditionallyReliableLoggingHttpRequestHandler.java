package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
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
                    log.warn("Got error: " + t.getMessage());
                    ctx.close();
                } else {
                    try {
                        super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } else {
            super.channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        }
    }
}
