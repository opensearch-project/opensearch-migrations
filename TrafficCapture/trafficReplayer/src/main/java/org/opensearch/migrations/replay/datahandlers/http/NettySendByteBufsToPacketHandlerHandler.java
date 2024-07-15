package org.opensearch.migrations.replay.datahandlers.http;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is responsible for sending the ByteBufs to the downstream packet receiver,
 * which in many cases will be the thing that sends the request over the network.
 *
 * Most of the logic within this class is to convert between ChannelFutures (netty's
 * futures) and CompletableFutures (Java's construct that came after).
 */
@Slf4j
public class NettySendByteBufsToPacketHandlerHandler<R> extends ChannelInboundHandlerAdapter {
    final IPacketFinalizingConsumer<R> packetReceiver;
    // final Boolean value indicates if the handler received a LastHttpContent or EndOfInput message
    TrackedFuture<String, Boolean> currentFuture;
    private AtomicReference<TrackedFuture<String, TransformedOutputAndResult<R>>> packetReceiverCompletionFutureRef;
    IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext;

    public NettySendByteBufsToPacketHandlerHandler(
        IPacketFinalizingConsumer<R> packetReceiver,
        IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext
    ) {
        this.packetReceiver = packetReceiver;
        this.packetReceiverCompletionFutureRef = new AtomicReference<>();
        this.httpTransactionContext = httpTransactionContext;
        currentFuture = TrackedFuture.Factory.completedFuture(
            null,
            () -> "currentFuture for NettySendByteBufsToPacketHandlerHandler initialized to the base case for "
                + httpTransactionContext
        );
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        log.debug("Handler removed for context " + ctx + " hash=" + System.identityHashCode(ctx));
        log.trace("HR: old currentFuture=" + currentFuture);
        if (currentFuture.future.isDone()) {
            if (currentFuture.future.isCompletedExceptionally()) {
                packetReceiverCompletionFutureRef.set(
                    currentFuture.getDeferredFutureThroughHandle(
                        (v, t) -> TextTrackedFuture.failedFuture(t, () -> "fixed failure"),
                        () -> "handlerRemoved: packetReceiverCompletionFuture receiving exceptional value"
                    )
                );
                return;
            } else if (currentFuture.get() == null) {
                log.info(
                    "The handler responsible for writing data to the server was detached before writing "
                        + "bytes.  Throwing a NoContentException so that the calling context can handle appropriately."
                );
                packetReceiverCompletionFutureRef.set(
                    TextTrackedFuture.failedFuture(
                        new NoContentException(),
                        () -> "Setting NoContentException to the exposed CompletableFuture"
                            + " of NettySendByteBufsToPacketHandlerHandler"
                    )
                );
                return;
            }
            // fall-through
        }

        var packetReceiverCompletionFuture = currentFuture.getDeferredFutureThroughHandle((v1, t1) -> {
            assert v1 != null
                : "expected in progress Boolean to be not null since null should signal that work was never started";
            var transformationStatus = v1.booleanValue()
                ? HttpRequestTransformationStatus.COMPLETED
                : HttpRequestTransformationStatus.ERROR;
            return packetReceiver.finalizeRequest()
                .getDeferredFutureThroughHandle(
                    (v2, t2) -> wrapFinalizedResultWithExceptionHandling(t1, v2, t2, transformationStatus),
                    () -> "handlerRemoved: NettySendByteBufsToPacketHandlerHandler is setting the completed value for its "
                        + "packetReceiverCompletionFuture, after the packets have been finalized "
                        + "to the packetReceiver"
                );
        }, () -> "handlerRemoved: waiting for the currentFuture to finish");
        currentFuture = packetReceiverCompletionFuture.getDeferredFutureThroughHandle(
            (v, t) -> TextTrackedFuture.<Boolean>completedFuture(
                true,
                () -> "ignoring return type of packetReceiver.finalizeRequest() but waiting for it to finish"
            ),
            () -> "Waiting for packetReceiver.finalizeRequest() and will return once that is done"
        );
        packetReceiverCompletionFutureRef.set(packetReceiverCompletionFuture);
        log.trace("HR: new currentFuture=" + currentFuture);
        super.handlerRemoved(ctx);
    }

    private static <R> TextTrackedFuture<TransformedOutputAndResult<R>> wrapFinalizedResultWithExceptionHandling(
        Throwable t1,
        R v2,
        Throwable t2,
        HttpRequestTransformationStatus transformationStatus
    ) {
        if (t1 != null) {
            return TextTrackedFuture.<TransformedOutputAndResult<R>>failedFuture(
                t1,
                () -> "fixed failure from currentFuture.getDeferredFutureThroughHandle()"
            );
        } else if (t2 != null) {
            return TextTrackedFuture.<TransformedOutputAndResult<R>>failedFuture(
                t2,
                () -> "fixed failure from packetReceiver.finalizeRequest()"
            );
        } else {
            return TextTrackedFuture.completedFuture(
                Optional.ofNullable(v2)
                    .map(r -> new TransformedOutputAndResult<R>(r, transformationStatus, null))
                    .orElse(null),
                () -> "fixed value from packetReceiver.finalizeRequest()"
            );
        }
    }

    public TrackedFuture<String, TransformedOutputAndResult<R>> getPacketReceiverCompletionFuture() {
        assert packetReceiverCompletionFutureRef.get() != null
            : "expected close() to have removed the handler and for this to be non-null";
        return packetReceiverCompletionFutureRef.get();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        currentFuture = TrackedFuture.Factory.failedFuture(
            cause,
            () -> "NettySendByteBufsToPacketHandlerHandler got an exception"
        );
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            log.trace(
                "read the following message and sending it to consumeBytes: "
                    + msg
                    + " hashCode="
                    + System.identityHashCode(msg)
                    + " ctx hash="
                    + System.identityHashCode(ctx)
            );
            var bb = (ByteBuf) msg;
            log.atTrace().setMessage(() -> "Send bb.refCnt=" + bb.refCnt() + " " + System.identityHashCode(bb)).log();
            // I don't want to capture the *this* object, the preexisting value of the currentFuture field only
            final var preexistingFutureForCapture = currentFuture;
            var numBytesToSend = bb.readableBytes();
            currentFuture = currentFuture.getDeferredFutureThroughHandle((v, t) -> {
                try {
                    if (t != null) {
                        log.atInfo()
                            .setCause(t)
                            .setMessage(
                                () -> "got exception from a previous future that "
                                    + "will prohibit sending any more data to the packetReceiver"
                            )
                            .log();
                        return TextTrackedFuture.failedFuture(t, () -> "failed previous future");
                    } else {
                        log.atTrace()
                            .setMessage(
                                () -> "chaining consumingBytes with "
                                    + msg
                                    + " lastFuture="
                                    + preexistingFutureForCapture
                            )
                            .log();
                        var rval = packetReceiver.consumeBytes(bb);
                        log.atTrace()
                            .setMessage(() -> "packetReceiver.consumeBytes()=" + rval + " bb.refCnt=" + bb.refCnt())
                            .log();
                        return rval.map(
                            cf -> cf.thenApply(ignore -> false),
                            () -> "NettySendByteBufsToPacketHandlerHandler.channelRead()'s future is "
                                + "going to return a completedValue of false to indicate that more "
                                + "packets may need to be sent"
                        );
                    }
                } finally {
                    bb.release();
                }
            },
                () -> "NettySendByteBufsToPacketHandlerHandler.channelRead waits for the previous future "
                    + "to finish before writing the next set of "
                    + numBytesToSend
                    + " bytes "
            );
            log.trace("CR: new currentFuture=" + currentFuture);
        } else if (msg instanceof LastHttpContent || msg instanceof EndOfInput) {
            currentFuture = currentFuture.map(
                cf -> cf.thenApply(ignore -> true),
                () -> "this NettySendByteBufsToPacketHandlerHandler.channelRead()'s future is prepared to return a "
                    + "completedValue of true since the "
                    + msg
                    + " object has been received"
            );
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
