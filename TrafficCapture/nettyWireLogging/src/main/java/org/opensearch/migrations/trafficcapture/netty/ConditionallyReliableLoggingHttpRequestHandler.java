package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandler extends LoggingHttpRequestHandler {
    private final Predicate<DefaultHttpRequest> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(IChannelConnectionCaptureSerializer trafficOffloader,
                                                          Predicate<DefaultHttpRequest> headerPredicateForWhenToBlock) {
        super(trafficOffloader);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, DefaultHttpRequest httpRequest) throws Exception {
        if (shouldBlockPredicate.test(httpRequest)) {
            ctx.channel().attr(AttributeKey.valueOf("isBlocking")).set(true);
            trafficOffloader.setIsBlockingMetadata(true);
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                if (t != null) {
                    log.warn("Got error: "+t.getMessage());
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
