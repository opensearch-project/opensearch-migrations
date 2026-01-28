package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.transform.IAuthTransformer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyJsonContentAuthSigner extends ChannelInboundHandlerAdapter {
    IAuthTransformer.StreamingFullMessageTransformer signer;
    HttpJsonRequestWithFaultingPayload httpMessage;
    List<HttpContent> httpContentsBuffer;

    public NettyJsonContentAuthSigner(IAuthTransformer.StreamingFullMessageTransformer signer) {
        this.signer = signer;
        this.httpContentsBuffer = new ArrayList<>();
        httpMessage = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonRequestWithFaultingPayload) {
            httpMessage = (HttpJsonRequestWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var httpContent = (HttpContent) msg;
            httpContentsBuffer.add(httpContent);
            signer.consumeNextPayloadPart(httpContent.content().nioBuffer());
            if (msg instanceof LastHttpContent) {
                signer.finalizeSignature(httpMessage);
                flushDownstream(ctx);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private boolean flushDownstream(ChannelHandlerContext ctx) {
        boolean messageFlushed = httpMessage != null || !httpContentsBuffer.isEmpty();
        if (httpMessage != null) {
            ctx.fireChannelRead(httpMessage);
            httpMessage = null;
        }
        httpContentsBuffer.forEach(ctx::fireChannelRead);
        httpContentsBuffer.clear();
        return messageFlushed;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        boolean messageFlushed = flushDownstream(ctx);
        if (messageFlushed) {
            log.atWarn()
                .setMessage("Failed to sign message due to handler removed before the end of the http contents were received")
                .log();
        }
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        boolean messageFlushed = flushDownstream(ctx);
        if (messageFlushed) {
            log.atWarn()
                .setMessage("Failed to sign message due to channel unregistered"
                        + " before the end of the http contents were received").log();
        }
        super.channelUnregistered(ctx);
    }
}
