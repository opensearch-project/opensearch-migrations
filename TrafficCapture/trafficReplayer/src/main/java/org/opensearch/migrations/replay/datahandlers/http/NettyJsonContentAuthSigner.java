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
    List<HttpContent> receivedHttpContents;

    public NettyJsonContentAuthSigner(IAuthTransformer.StreamingFullMessageTransformer signer) {
        this.signer = signer;
        this.receivedHttpContents = new ArrayList<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            httpMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            receivedHttpContents.add(((HttpContent) msg).retainedDuplicate());
            var httpContent = (HttpContent) msg;
            signer.consumeNextPayloadPart(httpContent.content().nioBuffer());
            if  (msg instanceof LastHttpContent) {
                finalizeSignature(ctx);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void finalizeSignature(ChannelHandlerContext ctx) {
        signer.finalizeSignature(httpMessage);
        ctx.fireChannelRead(httpMessage);
        receivedHttpContents.stream().forEach(content->{
            ctx.fireChannelRead(content);
            content.content().release();
        });
    }
}