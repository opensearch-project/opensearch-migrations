package org.opensearch.migrations.replay.netty;

import java.util.function.Consumer;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BacksideHttpWatcherHandler extends SimpleChannelInboundHandler<HttpObject> {

    private AggregatedRawResponse.Builder aggregatedRawResponseBuilder;
    private boolean doneReadingRequest;
    private boolean awaitingFinalAfterInterim;
    Consumer<AggregatedRawResponse> responseCallback;

    public BacksideHttpWatcherHandler(AggregatedRawResponse.Builder aggregatedRawResponseBuilder) {
        this.aggregatedRawResponseBuilder = aggregatedRawResponseBuilder;
        doneReadingRequest = false;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpResponse) {
            var response = (HttpResponse) msg;
            if (isInterimResponse(response)) {
                aggregatedRawResponseBuilder.addInterimResponsePacket(encodeHttpResponse(response));
                awaitingFinalAfterInterim = true;
            } else {
                aggregatedRawResponseBuilder.addHttpParsedResponseObject(response);
                awaitingFinalAfterInterim = false;
            }
        }
        if (msg instanceof LastHttpContent && !awaitingFinalAfterInterim) {
            triggerResponseCallbackAndRemoveCallback();
        } else if (msg instanceof LastHttpContent) {
            // LastHttpContent of an interim 1xx; the final response is still coming.
            awaitingFinalAfterInterim = false;
        }
    }

    private static boolean isInterimResponse(HttpResponse response) {
        var status = response.status();
        return status.codeClass() == HttpStatusClass.INFORMATIONAL && status.code() != 101;
    }

    private static byte[] encodeHttpResponse(HttpResponse response) {
        var encoder = new EmbeddedChannel(new HttpResponseEncoder());
        try {
            encoder.writeOutbound(response);
            encoder.writeOutbound(new DefaultLastHttpContent());
            var sb = new StringBuilder();
            ByteBuf buf;
            while ((buf = encoder.readOutbound()) != null) {
                try {
                    sb.append(buf.toString(java.nio.charset.StandardCharsets.ISO_8859_1));
                } finally {
                    buf.release();
                }
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        triggerResponseCallbackAndRemoveCallback();
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(@NonNull ChannelHandlerContext ctx) throws Exception {
        triggerResponseCallbackAndRemoveCallback();
        super.channelInactive(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        triggerResponseCallbackAndRemoveCallback();
        super.channelUnregistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        aggregatedRawResponseBuilder.addErrorCause(cause);
        triggerResponseCallbackAndRemoveCallback();
        // AggregatedRawResponseBuilder will contain exception context so
        // Exception caught event should not to propagate downstream
    }

    private void triggerResponseCallbackAndRemoveCallback() {
        log.atTrace().setMessage("triggerResponseCallbackAndRemoveCallback, callback={}")
            .addArgument(this.responseCallback).log();
        doneReadingRequest = true;
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
}
