package org.opensearch.migrations.replay.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Filters informational HTTP responses so target aggregation sees one terminal response per request.
 *
 * <p>When a decoded 1xx response begins, the handler releases that response and all following content through
 * its {@link LastHttpContent}, then resumes forwarding the stream. This prevents an interim response such as
 * {@code 100 Continue} from being mistaken for the target attempt's final result or consuming the request's
 * response slot.</p>
 *
 * <p>{@code 101 Switching Protocols} is preserved because it is terminal for HTTP rather than a prelude to
 * another HTTP response. Replay does not support the upgraded protocol, but forwarding 101 allows that
 * unsupported exchange to terminate explicitly instead of waiting forever for a later response.</p>
 *
 * <p>TODO(POST1): Preserve target interim responses through target aggregation and tuple output instead of
 * discarding them here. Use https://github.com/opensearch-project/opensearch-migrations/pull/3000 as the
 * implementation starting point.</p>
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
