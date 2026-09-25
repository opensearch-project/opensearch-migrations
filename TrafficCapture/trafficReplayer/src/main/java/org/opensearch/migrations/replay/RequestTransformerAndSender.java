package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: IReplayContexts ReplayEngine RequestResponsePacketPair . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
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

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// RequestTransformerAndSender.getRetryCheckVisitor ->
//     NettyPacketToHttpConsumer.ActiveAttempt.classify
// RequestTransformerAndSender.getRetryCheckVisitor -> RequestReplayOwner.evaluateTargetResponse
// RequestTransformerAndSender.getRetryCheckVisitor -> RequestReplayOwner.applyRetryDecision
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
    RequestSenderOrchestrator.RetryVisitor<T>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufListProducer> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture,
                         Consumer<AggregatedRawResponse> resultsConsumer) {
        var perRequestStatefulVisitor =
            retryVisitorFactory.getRetryCheckVisitor(transformedResult, finishedAccumulatingResponseFuture);
        return new RequestSenderOrchestrator.RetryVisitor<>() {
            @Override
            public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<T>> visit(
                io.netty.buffer.ByteBuf requestBytes,
                TargetAttemptOutcome<AggregatedRawResponse> outcome
            ) {
                outcome.visit(new TargetAttemptOutcome.Visitor<
                    AggregatedRawResponse,
                    Void>() {
                    @Override
                    public Void onTargetResponseObtained(
                        TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse> obtained
                    ) {
                        resultsConsumer.accept(obtained.response());
                        return null;
                    }

                    @Override
                    public Void onNoTargetResponseObtained(
                        TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse> notObtained
                    ) {
                        return null;
                    }
                });
                return perRequestStatefulVisitor.visit(requestBytes, outcome);
            }

            @Override
            public void close() {
                perRequestStatefulVisitor.close();
            }
        };
    }

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Do nothing but give subclasses the opportunity to do more.
     */
// REBUILD-LIMBO-START(G5)
/*
    protected void perResponseConsumer(AggregatedRawResponse summary,
                                       HttpRequestTransformationStatus transformationStatus,
                                       IReplayContexts.IReplayerHttpTransactionContext context) {
*/
// REBUILD-LIMBO-END(G5)
// REBUILD-LIMBO-ESCAPED-LINE(G5):         /* only present for extension purposes */
// REBUILD-LIMBO-START(G5)
/*
    }

*/
// REBUILD-LIMBO-END(G5)
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
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// RequestTransformerAndSender.transformAndSendRequest(7-argument) ->
//     RequestReplayOwner.beginPreparation
// RequestTransformerAndSender.transformAndSendRequest(7-argument) ->
//     RequestReplayOwner.applyPreparationResult
// RequestTransformerAndSender.transformAndSendRequest(7-argument) ->
//     RequestReplayOwner.startAttempt
// RequestTransformerAndSender.transformAndSendRequest(7-argument) ->
//     RequestReplayOwner.tryStartTuple
// RequestTransformerAndSender.transformAndSendRequest(8-argument) ->
//     RequestReplayOwner.beginPreparation
// RequestTransformerAndSender.transformAndSendRequest(8-argument) ->
//     RequestReplayOwner.applyPreparationResult
// RequestTransformerAndSender.transformAndSendRequest(8-argument) ->
//     RequestReplayOwner.startAttempt
// RequestTransformerAndSender.transformAndSendRequest(8-argument) ->
//     RequestReplayOwner.tryStartTuple
// The two baseline overloads were consolidated into the retained ten-argument predecessor below
// before the live owner path split preparation, attempts, and tuple settlement.
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
    public TrackedFuture<String, T> transformAndSendRequest(
        PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
        ReplayEngine replayEngine,
        PartitionGenerationId partitionGenerationId,
        TrackedFuture<String, RequestResponsePacketPair> finishedAccumulatingResponseFuture,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull Instant start,
        @NonNull Instant end,
        Supplier<Stream<byte[]>> packetsSupplier,
        Duration quiescentDurationForRequest,
        @NonNull TargetConnectionOwner.RequestProcessingRegistration processingRegistration
    ) {
        try {
            return replayEngine.scheduleRequestLifecycle(
                partitionGenerationId,
                ctx,
                start,
                end,
                () -> transformAllData(inputRequestTransformerFactory.create(ctx), packetsSupplier),
                transformedRequest -> getRetryCheckVisitor(
                    transformedRequest,
                    finishedAccumulatingResponseFuture,
                    response -> perResponseConsumer(
                        response,
                        transformedRequest.transformationStatus,
                        ctx
                    )
                ),
                transformationStatus -> {
                    @SuppressWarnings("unchecked")
                    var filtered = (T) new TransformedTargetRequestAndResponseList(null, transformationStatus);
                    return filtered;
                },
                quiescentDurationForRequest,
                processingRegistration
            );
        } catch (Exception e) {
            log.debug("Caught exception while admitting the request lifecycle", e);
            return TextTrackedFuture.failedFuture(e, () -> "TrafficReplayer.requestLifecycle");
        }
    }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// RequestTransformerAndSender.transformAllData -> TrafficReplayerTopLevel.deployedRequestPreparer
//     [feed captured packets through request transformation and finalize the transformed request].
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
