package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.util.function.Predicate;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;
import org.opensearch.migrations.trafficcapture.netty.tracing.IWireCaptureContexts;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import lombok.Lombok;
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
        @NonNull Predicate<HttpRequest> headerPredicateForWhenToBlock
    ) throws IOException {
        super(rootContext, nodeId, connectionId, trafficOffloaderFactory, requestCapturePredicate);
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
            trafficOffloader.flushCommitAndResetStream(false).whenComplete((result, t) -> {
                log.atInfo().setMessage(() -> "Done flushing").log();

                if (t != null) {
                    // This is a spot where we would benefit from having a behavioral policy that different users
                    // could set as needed. Some users may be fine with just logging a failed offloading of a request
                    // where other users may want to stop entirely. JIRA here:
                    // https://opensearch.atlassian.net/browse/MIGRATIONS-1276
                    log.atWarn()
                        .setCause(t)
                        .setMessage("Error offloading the request, but forwarding it to the service anyway")
                        .log();
                    ReferenceCountUtil.release(msg);
                    messageContext.addCaughtException(t);
                }
                try {
                    super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
        } else {
            assert messageContext instanceof IWireCaptureContexts.IRequestContext;
            super.channelFinishedReadingAnHttpMessage(ctx, msg, shouldCapture, httpRequest);
        }
    }
}
