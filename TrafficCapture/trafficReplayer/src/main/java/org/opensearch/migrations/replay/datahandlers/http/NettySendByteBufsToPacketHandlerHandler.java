package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public class NettySendByteBufsToPacketHandlerHandler extends ChannelInboundHandlerAdapter {
    final IPacketToHttpHandler packetReceiver;
    // final Boolean value indicates if the handler received a LastHttpContent message
    // TODO - make this threadsafe.  calls may come in on different threads
    CompletableFuture<Boolean> currentFuture;
    private AtomicReference<CompletableFuture<AggregatedRawResponse>> packetReceiverCompletionFutureRef;

    public NettySendByteBufsToPacketHandlerHandler(IPacketToHttpHandler packetReceiver) {
        this.packetReceiver = packetReceiver;
        this.packetReceiverCompletionFutureRef = new AtomicReference<>();
        currentFuture = CompletableFuture.completedFuture(null);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        log.debug("Handler removed for context " + ctx + " hash=" + System.identityHashCode(ctx));
        log.trace("HR: old currentFuture="+currentFuture);
        if (currentFuture.isDone() && currentFuture.get() == null) {
            packetReceiverCompletionFutureRef.set(CompletableFuture.failedFuture(new NoContentException()));
            return;
        }
        CompletableFuture<AggregatedRawResponse> packetReceiverCompletionFuture =
                new CompletableFuture<>();
        packetReceiverCompletionFutureRef.set(packetReceiverCompletionFuture);
        currentFuture = currentFuture.whenComplete((v1,t1) -> {
            assert v1 != null :
                    "expected in progress Boolean to be not null since null should signal that work was never started";
            var transformationStatus = v1.booleanValue() ?
                    AggregatedRawResponse.HttpRequestTransformationStatus.COMPLETED :
                    AggregatedRawResponse.HttpRequestTransformationStatus.ERROR;
            packetReceiver.finalizeRequest()
                    .whenComplete((v2, t2) -> {
                        if (t1 != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t1);
                        } else if (t2 != null) {
                            packetReceiverCompletionFuture.completeExceptionally(t2);
                        } else {
                            packetReceiverCompletionFuture
                                    .complete(AggregatedRawResponse.addStatusIfPresent(v2, transformationStatus));
                        }
                    });
        });
        log.trace("HR: new currentFuture="+currentFuture);
        super.handlerRemoved(ctx);
    }

    public CompletableFuture<AggregatedRawResponse> getPacketReceiverCompletionFuture() {
        assert packetReceiverCompletionFutureRef.get() != null :
                "expected close() to have removed the handler and for this to be non-null";
        return packetReceiverCompletionFutureRef.get();
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
                return rval.thenApply(ignore->false); // false means that the handler hasn't reached the end of the data
            });
            log.trace("CR: new currentFuture="+currentFuture);
        } else if (msg instanceof LastHttpContent) {
            currentFuture = currentFuture.thenApply(ignore->true);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

}
