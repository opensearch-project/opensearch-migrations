package org.opensearch.migrations.replay.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;

import java.util.function.Consumer;

@Slf4j
public class BacksideHttpWatcherHandler extends ChannelInboundHandlerAdapter {

    private AggregatedRawResponse.Builder aggregatedRawResponseBuilder;
    private boolean doneReadingRequest; // later, when connections are reused, switch this to a counter?
    Consumer<AggregatedRawResponse> responseCallback;

    public BacksideHttpWatcherHandler(AggregatedRawResponse.Builder aggregatedRawResponseBuilder) {
        this.aggregatedRawResponseBuilder = aggregatedRawResponseBuilder;
        doneReadingRequest = false;
    }

    @Override
    public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
        try {
            if (msg instanceof LastHttpContent) {
                doneReadingRequest = true;
                triggerResponseCallbackAndRemoveCallback();
                super.channelReadComplete(ctx);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        triggerResponseCallbackAndRemoveCallback();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        aggregatedRawResponseBuilder.addErrorCause(cause);
        triggerResponseCallbackAndRemoveCallback();
        super.exceptionCaught(ctx, cause);
    }

    private void triggerResponseCallbackAndRemoveCallback() {
        log.atTrace().setMessage(()->"triggerResponseCallbackAndRemoveCallback, callback="+this.responseCallback).log();
        if (this.responseCallback != null) {
            // this method may be re-entrant upon calling the callback, so make sure that we don't loop
            var originalResponseCallback = this.responseCallback;
            this.responseCallback = null;
            originalResponseCallback.accept(aggregatedRawResponseBuilder.build());
            aggregatedRawResponseBuilder = null;
        }
    }

    public void addCallback(Consumer<AggregatedRawResponse> callback) {
        if (aggregatedRawResponseBuilder == null) {
            throw new IllegalStateException("Callback was already triggered for the aggregated response");
        }
        if (doneReadingRequest) {
            log.trace("calling callback because we're done reading the request");
            callback.accept(aggregatedRawResponseBuilder.build());
        } else {
            log.trace("setting the callback to fire later");
            this.responseCallback = callback;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        doneReadingRequest = true;
        log.trace("inactive channel - closing");
        triggerResponseCallbackAndRemoveCallback();
        super.channelInactive(ctx);
    }

}
