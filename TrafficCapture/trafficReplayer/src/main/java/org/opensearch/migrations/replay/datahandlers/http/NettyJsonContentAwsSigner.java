package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.opensearch.migrations.replay.SigV4Signer;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

public class NettyJsonContentAwsSigner extends ChannelInboundHandlerAdapter {
    SigV4Signer signer;
    HttpJsonMessageWithFaultingPayload httpMessage;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            signer = new SigV4Signer((IHttpMessage) msg, DefaultCredentialsProvider.builder().build());
        } else if (msg instanceof HttpContent) {
            var httpContent = (HttpContent) msg;
            signer.processNextPayload(httpContent.content().nioBuffer());
            if  (msg instanceof LastHttpContent) {
                finalizeSignature();
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void finalizeSignature() {

    }
}