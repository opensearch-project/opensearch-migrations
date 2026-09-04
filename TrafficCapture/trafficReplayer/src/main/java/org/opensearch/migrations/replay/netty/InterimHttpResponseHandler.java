package org.opensearch.migrations.replay.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Removes interim HTTP responses from a decoded response stream.
 *
 * <p>HTTP 101 is intentionally preserved because it changes protocols instead of preceding another
 * HTTP response. The replayer does not support the upgraded protocol, but treating 101 as terminal
 * allows that exchange to fail promptly instead of waiting for a response that will never arrive.
 */
public class InterimHttpResponseHandler extends ChannelInboundHandlerAdapter {
    private boolean discardingInterimResponse;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpResponse response) {
            discardingInterimResponse = isInterim(response);
        }

        if (discardingInterimResponse) {
            if (msg instanceof LastHttpContent) {
                discardingInterimResponse = false;
            }
            ReferenceCountUtil.release(msg);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private static boolean isInterim(HttpResponse response) {
        var statusCode = response.status().code();
        return statusCode >= 100 && statusCode < 200 && statusCode != 101;
    }
}
