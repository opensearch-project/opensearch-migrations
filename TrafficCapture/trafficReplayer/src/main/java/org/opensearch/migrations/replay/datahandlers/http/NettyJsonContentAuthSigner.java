package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.opensearch.migrations.transform.IAuthTransformer;

import java.util.ArrayList;
import java.util.List;

public class NettyJsonContentAuthSigner extends ChannelInboundHandlerAdapter {
    IAuthTransformer.StreamingFullMessageTransformer signer;
    HttpJsonMessageWithFaultingPayload httpMessage;
    List<HttpContent> httpContentsBuffer;

    public NettyJsonContentAuthSigner(IAuthTransformer.StreamingFullMessageTransformer signer) {
        this.signer = signer;
        this.httpContentsBuffer = new ArrayList<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            httpMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var httpContent = (HttpContent) msg;
            httpContentsBuffer.add(httpContent);
            signer.consumeNextPayloadPart(httpContent.content().nioBuffer());
            if  (msg instanceof LastHttpContent) {
                signer.finalizeSignature(httpMessage);
                flushDownstream(ctx);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void flushDownstream(ChannelHandlerContext ctx) {
        if(httpMessage != null) {
            ctx.fireChannelRead(httpMessage);
            httpMessage = null;
        }
        httpContentsBuffer.forEach(ctx::fireChannelRead);
        httpContentsBuffer.clear();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        flushDownstream(ctx);
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        flushDownstream(ctx);
        super.channelUnregistered(ctx);
    }
}