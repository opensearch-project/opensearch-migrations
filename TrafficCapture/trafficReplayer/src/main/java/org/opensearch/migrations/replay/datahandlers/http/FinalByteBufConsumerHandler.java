package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class FinalByteBufConsumerHandler extends ChannelInboundHandlerAdapter {
    final IPacketToHttpHandler packetReceiver;

    public final CompletableFuture<AggregatedRawResponse> packetReceiverCompletionFuture;

    public FinalByteBufConsumerHandler(IPacketToHttpHandler packetReceiver) {
        this.packetReceiver = packetReceiver;
        this.packetReceiverCompletionFuture = new CompletableFuture<>();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
//            packetReceiver.finalizeRequest()
//                    .whenComplete((v,t) -> {
//                        if (t != null) {
//                            packetReceiverCompletionFuture.completeExceptionally(t);
//                        } else {
//                            packetReceiverCompletionFuture.complete(v);
//                        }
//                    });
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            packetReceiver.consumeBytes((ByteBuf) msg)
                    .whenComplete((v, t) -> {
                        if (t != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t);
                        } else {
                            try {
                                super.channelRead(ctx, msg);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        }
    }

}
