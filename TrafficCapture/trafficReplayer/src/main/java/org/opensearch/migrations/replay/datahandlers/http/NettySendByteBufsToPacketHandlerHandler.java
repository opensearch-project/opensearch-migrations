package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class NettySendByteBufsToPacketHandlerHandler extends ChannelInboundHandlerAdapter {
    final IPacketToHttpHandler packetReceiver;
    CompletableFuture<Void> currentFuture;

    public final CompletableFuture<AggregatedRawResponse> packetReceiverCompletionFuture;

    public NettySendByteBufsToPacketHandlerHandler(IPacketToHttpHandler packetReceiver) {
        this.packetReceiver = packetReceiver;
        this.packetReceiverCompletionFuture = new CompletableFuture<>();
        currentFuture = CompletableFuture.completedFuture(null);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        log.debug("Handler removed for context " + ctx + " hash=" + System.identityHashCode(ctx));
        log.trace("HR: old currentFuture="+currentFuture);
        currentFuture = currentFuture.whenComplete((v1,t1) -> {
            packetReceiver.finalizeRequest()
                    .whenComplete((v2, t2) -> {
                        if (t1 != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t1);
                        } else if (t2 != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t2);
                        } else {
                            packetReceiverCompletionFuture.complete(v2);
                        }
                    });
        });
        log.trace("HR: new currentFuture="+currentFuture);
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            log.trace("read the following message and sending it to consumeBytes: " + msg +
                    " hashCode=" + System.identityHashCode(msg) +
                    " ctx hash=" + System.identityHashCode(ctx));
            var bb = ((ByteBuf) msg).retain();
            log.trace("CR: old currentFuture="+currentFuture);
            currentFuture = currentFuture.thenCompose(v-> {
                log.trace("chaining consumingBytes with "+msg + " hashCode=" + System.identityHashCode(msg) +
                        " ctx hash=" + System.identityHashCode(ctx));
                var rval = packetReceiver.consumeBytes(bb);
                bb.release();
                return rval;
            });
            log.trace("CR: new currentFuture="+currentFuture);
        }
    }

}
