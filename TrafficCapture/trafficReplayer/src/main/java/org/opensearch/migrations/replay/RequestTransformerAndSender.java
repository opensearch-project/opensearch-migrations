package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class RequestTransformerAndSender<T> {

    protected final IRetryVisitorFactory<T> retryVisitorFactory;

    RequestSenderOrchestrator.RetryVisitor<T>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufList> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture,
                         Consumer<AggregatedRawResponse> resultsConsumer) {
        var perRequestStatefulVisitor =
            retryVisitorFactory.getRetryCheckVisitor(transformedResult, finishedAccumulatingResponseFuture);
        return (requestBytes, aggResponse, t) -> {
            resultsConsumer.accept(aggResponse);
            if (!shouldRetry()) {
                return TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.DONE,
                        null),
                    () -> "Returning a future to NOT retry because the class is currently prohibiting retries" +
                        "");
            }
            if (t != null) {
                return TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.RETRY,
                        null),
                    () -> "Returning a future to retry due to a connection exception");
            } else {
                assert (aggResponse != null);
            }
            return perRequestStatefulVisitor.visit(requestBytes, aggResponse, t);
        };
    }

    /**
     * This is called by before passing the response through the visitor returned by the retryVisitorFactory.
     * This is used to suppress retrying when the system is being shut down.
     */
    protected boolean shouldRetry() {
        return true;
    }

    /**
     * Do nothing but give subclasses the opportunity to do more.
     */
    protected void perResponseConsumer(AggregatedRawResponse summary,
                                       HttpRequestTransformationStatus transformationStatus,
                                       IReplayContexts.IReplayerHttpTransactionContext context) {
        /* only present for extension purposes */
    }

    /**
     * Take a source request and transform it (on the work thread that we'll also SEND the transformed
     * request).  If an exception happens during transformation, the returned TrackedFuture will have
     * an exceptional completion.  The transformed request future is composed with a method that sends
     * the request and awaits a response.  Specifically, a response that is returned through the visitor
     * that will retry in case of any exceptional or error (status code) occurrences.<br><br>
     *
     * If there is an error in calling the replayEngine, that exception is trapped and will be returned
     * immediately in a TrackedFuture with the Exception.
     *
     * @return An exceptional value in the TrackedFuture if the replayEngine calls throw immediately
     * or if transformation fails.  A completed value of a TransformedTargetRequestAndResponseList,
     * which may include exceptions within individual request's AggregatedRawResponse.getError() fields
     * and/or results from the target server.  Notice that exceptions due to renegotiating a connection
     * will NOT be included as responses since that's independent of the outgoing request (since bytes
     * hadn't begun to be sent).
     */
    public TrackedFuture<String, T> transformAndSendRequest(
        PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
        ReplayEngine replayEngine,
        TrackedFuture<String, RequestResponsePacketPair> finishedAccumulatingResponseFuture,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull Instant start,
        @NonNull Instant end,
        Supplier<Stream<byte[]>> packetsSupplier) {
        return transformAndSendRequest(inputRequestTransformerFactory, replayEngine,
            finishedAccumulatingResponseFuture, ctx, start, end, packetsSupplier, null);
    }

    public TrackedFuture<String, T> transformAndSendRequest(
        PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
        ReplayEngine replayEngine,
        TrackedFuture<String, RequestResponsePacketPair> finishedAccumulatingResponseFuture,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull Instant start,
        @NonNull Instant end,
        Supplier<Stream<byte[]>> packetsSupplier,
        Instant quiescentUntil) {
        try {
            var requestReadyFuture = replayEngine.scheduleTransformationWork(
                ctx,
                start,
                () -> transformAllData(inputRequestTransformerFactory.create(ctx), packetsSupplier)
            );
            log.atDebug().setMessage("request transform future for {} = {}")
                .addArgument(ctx)
                .addArgument(requestReadyFuture)
                .log();
            final Instant effectiveQuiescentUntil = quiescentUntil;
            return requestReadyFuture.thenCompose(
                transformedRequest -> replayEngine.scheduleRequest(
                    ctx,
                    start,
                    end,
                    transformedRequest.transformedOutput.size(),
                    transformedRequest.transformedOutput,
                    getRetryCheckVisitor(transformedRequest, finishedAccumulatingResponseFuture,
                        arr -> perResponseConsumer(arr, transformedRequest.transformationStatus, ctx)),
                    effectiveQuiescentUntil
                ),
                () -> "transitioning transformed packets onto the wire"
            );
        } catch (Exception e) {
            log.debug("Caught exception in transformAndSendRequest, so failing future");
            return TextTrackedFuture.failedFuture(e, () -> "TrafficReplayer.writeToSocketAndClose");
        }
    }

    private static <R> TrackedFuture<String, R> transformAllData(
        IPacketFinalizingConsumer<R> packetHandler,
        Supplier<Stream<byte[]>> packetSupplier
    ) {
        try {
            var logLabel = packetHandler.getClass().getSimpleName();
            var packets = packetSupplier.get().map(Unpooled::wrappedBuffer);
            packets.forEach(packetData -> {
                log.atDebug()
                    .setMessage("{} sending {} bytes to the packetHandler")
                    .addArgument(logLabel)
                    .addArgument(packetData::readableBytes)
                    .log();
                var consumeFuture = packetHandler.consumeBytes(packetData);
                log.atDebug().setMessage("{} consumeFuture = {}")
                    .addArgument(logLabel)
                    .addArgument(consumeFuture)
                    .log();
            });
            log.atDebug().setMessage("{}  done sending bytes, now finalizing the request").addArgument(logLabel).log();
            return packetHandler.finalizeRequest();
        } catch (Exception e) {
            log.atInfo()
                .setCause(e)
                .setMessage(
                    "Encountered an exception while transforming the http request.  "
                        + "The base64 gzipped traffic stream, for later diagnostic purposes, is: {}")
                .addArgument(() -> Utils.packetsToCompressedTrafficStream(packetSupplier.get()))
                .log();
            throw e;
        }
    }
}
