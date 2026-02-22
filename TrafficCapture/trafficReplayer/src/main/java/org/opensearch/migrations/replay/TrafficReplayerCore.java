package org.opensearch.migrations.replay;

import java.io.EOFException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class TrafficReplayerCore extends RequestTransformerAndSender<TransformedTargetRequestAndResponseList> {

    public interface IWorkTracker<T> {
        void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, TrackedFuture<String, T> completableFuture);

        void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey);

        boolean isEmpty();

        int size();
    }

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    protected final TrafficStreamLimiter liveTrafficStreamLimiter;
    protected final AtomicInteger successfulRequestCount;
    protected final AtomicInteger exceptionRequestCount;
    public final IRootReplayerContext topLevelContext;
    protected final IWorkTracker<Void> requestWorkTracker;


    protected final AtomicBoolean stopReadingRef;
    protected final AtomicReference<CompletableFuture<List<ITrafficStreamWithKey>>> nextChunkFutureRef;

    protected TrafficReplayerCore(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformer,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        TrafficStreamLimiter trafficStreamLimiter,
        IWorkTracker<Void> requestWorkTracker,
        IRetryVisitorFactory retryVisitorFactory
    ) {
        super(retryVisitorFactory);
        this.topLevelContext = context;

        if (serverUri.getPort() < 0) {
            throw new IllegalArgumentException("Port not present for URI: " + serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: " + serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: " + serverUri);
        }
        this.liveTrafficStreamLimiter = trafficStreamLimiter;
        this.requestWorkTracker = requestWorkTracker;
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformerSupplier, authTransformer);
        successfulRequestCount = new AtomicInteger();
        exceptionRequestCount = new AtomicInteger();
        nextChunkFutureRef = new AtomicReference<>();
        stopReadingRef = new AtomicBoolean();
    }

    protected abstract CompletableFuture<Void> shutdown(Error error);

    @AllArgsConstructor
    class TrafficReplayerAccumulationCallbacks implements AccumulationCallbacks {
        private final ReplayEngine replayEngine;
        private Consumer<SourceTargetCaptureTuple> resultTupleConsumer;
        private ITrafficCaptureSource trafficCaptureSource;

        @Override
        public Consumer<RequestResponsePacketPair> onRequestReceived(
            @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
            @NonNull HttpMessageAndTimestamp request
        ) {
            replayEngine.setFirstTimestamp(request.getFirstPacketTimestamp());

            var requestKey = ctx.getReplayerRequestKey();

            var finishedAccumulatingResponseFuture = new TextTrackedFuture<RequestResponsePacketPair>(
                () -> "waiting for response to be accumulated for " + ctx
            );
            finishedAccumulatingResponseFuture.future.whenComplete(
                (v, t) -> log.atDebug()
                    .setMessage("Done receiving captured stream for {}:{}")
                    .addArgument(ctx)
                    .addArgument(v.requestData)
                    .log()
            );

            var allWorkFinishedForTransactionFuture =
                sendRequestAfterGoingThroughWorkQueue(ctx, request, requestKey, finishedAccumulatingResponseFuture)
                    .getDeferredFutureThroughHandle(
                        // TODO - what if finishedAccumulatingResponseFuture completed exceptionally?
                        (arr, httpRequestException) -> finishedAccumulatingResponseFuture.thenCompose(
                            rrPair -> TextTrackedFuture.completedFuture(
                                handleCompletedTransaction(ctx, rrPair, arr, httpRequestException),
                                () -> "Synchronously committed results"
                            ),
                            () -> "logging summary"
                        ),
                        () -> "waiting for accumulation to combine with target response"
                    );

            assert !allWorkFinishedForTransactionFuture.future.isDone();
            log.trace("Adding " + requestKey + " to targetTransactionInProgressMap");
            requestWorkTracker.put(requestKey, allWorkFinishedForTransactionFuture);

            return finishedAccumulatingResponseFuture.future::complete;
        }

        /**
         * @see RequestTransformerAndSender#transformAndSendRequest
         */
        private TrackedFuture<String, TransformedTargetRequestAndResponseList> sendRequestAfterGoingThroughWorkQueue(
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            HttpMessageAndTimestamp request,
            UniqueReplayerRequestKey requestKey,
            TextTrackedFuture<RequestResponsePacketPair> finishedAccumulatingResponseFuture) {
            var workDequeuedByLimiterFuture = new TextTrackedFuture<TrafficStreamLimiter.WorkItem>(
                () -> "waiting for " + ctx + " to be queued and run through TrafficStreamLimiter"
            );
            var wi = liveTrafficStreamLimiter.queueWork(1, ctx, workDequeuedByLimiterFuture.future::complete);
            var httpSentRequestFuture = workDequeuedByLimiterFuture.thenCompose(
                    ignored -> transformAndSendRequest(replayEngine, request, finishedAccumulatingResponseFuture, ctx),
                    () -> "Waiting to get response from target"
                )
                .whenComplete(
                    (v, t) -> liveTrafficStreamLimiter.doneProcessing(wi),
                    () -> "releasing work item for the traffic limiter"
                );
            httpSentRequestFuture.future.whenComplete(
                (v, t) -> log.atTrace()
                    .setMessage("Summary response value for {} returned={}")
                    .addArgument(requestKey).addArgument(v)
                    .log());
            return httpSentRequestFuture;
        }

        Void handleCompletedTransaction(
            @NonNull IReplayContexts.IReplayerHttpTransactionContext context,
            RequestResponsePacketPair rrPair,
            TransformedTargetRequestAndResponseList summary,
            Throwable t
        ) {
            try (var httpContext = rrPair.getHttpTransactionContext()) {
                // if this comes in with a serious Throwable (not an Exception), don't bother
                // packaging it up and calling the callback.
                // Escalate it up out handling stack and shutdown.
                if (t == null || t instanceof Exception) {
                    try (var tupleHandlingContext = httpContext.createTupleContext()) {
                        packageAndWriteResponse(
                            tupleHandlingContext,
                            resultTupleConsumer,
                            rrPair,
                            summary,
                            (Exception) t
                        );
                    }
                    // Count the final outcome once per request (not per retry)
                    countFinalOutcome(summary, t);
                    commitTrafficStreams(rrPair.completionStatus, rrPair.trafficStreamKeysBeingHeld);
                    return null;
                } else {
                    log.atError().setCause(t)
                        .setMessage("Throwable passed to handle() for {}.  Rethrowing.").addArgument(context).log();
                    throw Lombok.sneakyThrow(t);
                }
            } catch (Error error) {
                log.atError().setCause(error)
                    .setMessage("Caught error and initiating TrafficReplayer shutdown").log();
                shutdown(error);
                throw error;
            } catch (Exception e) {
                log.atError().setMessage("Unexpected exception while sending the "
                        + "aggregated response and context for {} to the callback.  "
                        + "Proceeding, but the tuple receiver context may be compromised.")
                    .addArgument(context)
                    .setCause(e)
                    .log();
                throw e;
            } finally {
                var requestKey = context.getReplayerRequestKey();
                requestWorkTracker.remove(requestKey);
                log.trace("removed rrPair.requestData to " + "targetTransactionInProgressMap for " + requestKey);
            }
        }

        private void countFinalOutcome(TransformedTargetRequestAndResponseList summary, Throwable t) {
            if (t != null) {
                exceptionRequestCount.incrementAndGet();
            } else if (summary == null || summary.getResponseList().isEmpty()) {
                // no response to count
            } else {
                var lastResponse = summary.getResponseList().get(summary.getResponseList().size() - 1);
                if (lastResponse.getError() != null || summary.getTransformationStatus().isError()) {
                    exceptionRequestCount.incrementAndGet();
                } else {
                    successfulRequestCount.incrementAndGet();
                }
            }
        }

        @SneakyThrows
        private void commitTrafficStreams(
            RequestResponsePacketPair.ReconstructionStatus status,
            List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            commitTrafficStreams(
                status != RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
                trafficStreamKeysBeingHeld
            );
        }

        @SneakyThrows
        private void commitTrafficStreams(boolean shouldCommit, List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            if (trafficStreamKeysBeingHeld != null) {
                for (var tsk : trafficStreamKeysBeingHeld) {
                    tsk.getTrafficStreamsContext().close();
                    if (shouldCommit) {
                        trafficCaptureSource.commitTrafficStream(tsk);
                    }
                }
            }
        }

        @Override
        public void onConnectionClose(
            int channelInteractionNum,
            @NonNull IReplayContexts.IChannelKeyContext ctx,
            int channelSessionNumber,
            RequestResponsePacketPair.ReconstructionStatus status,
            @NonNull Instant timestamp,
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            if (status == RequestResponsePacketPair.ReconstructionStatus.REASSIGNED) {
                // Partition reassignment close — don't commit, just close tracing contexts
                commitTrafficStreams(false, trafficStreamKeysBeingHeld);
                return;
            }
            notifyConnectionDone(trafficStreamKeysBeingHeld);
            replayEngine.setFirstTimestamp(timestamp);
            var cf = replayEngine.closeConnection(channelInteractionNum, ctx, channelSessionNumber, timestamp);
            cf.map(
                f -> f.whenComplete((v, t) -> commitTrafficStreams(status, trafficStreamKeysBeingHeld)),
                () -> "closing the channel in the ReplayEngine"
            );
        }

        @Override
        public void onTrafficStreamsExpired(
            RequestResponsePacketPair.ReconstructionStatus status,
            @NonNull IReplayContexts.IChannelKeyContext ctx,
            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
        ) {
            notifyConnectionDone(trafficStreamKeysBeingHeld);
            commitTrafficStreams(status, trafficStreamKeysBeingHeld);
        }

        private void notifyConnectionDone(List<ITrafficStreamKey> keys) {
            if (keys != null && !keys.isEmpty()) {
                trafficCaptureSource.onConnectionDone(keys.get(0));
            }
        }

        @Override
        public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
            commitTrafficStreams(true, List.of(ctx.getTrafficStreamKey()));
        }

        private void packageAndWriteResponse(
            IReplayContexts.ITupleHandlingContext tupleHandlingContext,
            Consumer<SourceTargetCaptureTuple> tupleWriter,
            RequestResponsePacketPair rrPair,
            TransformedTargetRequestAndResponseList summary,
            Exception t
        ) {
            log.trace("done sending and finalizing data to the packet handler");

            if (t != null) {
                log.atError().setMessage("Got exception in CompletableFuture callback for {}")
                    .addArgument(tupleHandlingContext)
                    .setCause(t)
                    .log();
            }
            try (var requestResponseTuple = new SourceTargetCaptureTuple(tupleHandlingContext, rrPair, summary, t)) {
                log.atDebug()
                    .setMessage("Source/Target Request/Response tuple: {}").addArgument(requestResponseTuple).log();
                tupleWriter.accept(requestResponseTuple);
            }

            if (t != null) {
                throw new CompletionException(t);
            }
        }
    }

    /**
     * @see RequestTransformerAndSender#transformAndSendRequest
     */
    public TrackedFuture<String, TransformedTargetRequestAndResponseList> transformAndSendRequest(
        ReplayEngine replayEngine,
        HttpMessageAndTimestamp request,
        TrackedFuture<String, RequestResponsePacketPair> finishedAccumulatingResponseFuture,
        IReplayContexts.IReplayerHttpTransactionContext ctx
    ) {
        return transformAndSendRequest(
            inputRequestTransformerFactory,
            replayEngine,
            finishedAccumulatingResponseFuture,
            ctx,
            request.getFirstPacketTimestamp(),
            request.getLastPacketTimestamp(),
            request.packetBytes::stream);
    }

    @Override
    protected void perResponseConsumer(AggregatedRawResponse summary,
                                       HttpRequestTransformationStatus transformationStatus,
                                       IReplayContexts.IReplayerHttpTransactionContext context) {
        // Logging only — counting moved to handleCompletedTransaction to avoid double-counting on retries
        if (summary != null && summary.getError() != null) {
            log.atInfo().setCause(summary.getError())
                .setMessage("Exception for {}: ").addArgument(context).log();
        } else if (transformationStatus.isError()) {
            log.atInfo()
                .setCause(Optional.ofNullable(summary).map(AggregatedRawResponse::getError).orElse(null))
                .setMessage("Unknown error transforming {}: ")
                .addArgument(context)
                .log();
        } else if (summary == null) {
            log.atInfo().setMessage("No result at all for {}: ")
                .addArgument(context)
                .log();
        }
    }

    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
        ITrafficCaptureSource trafficChunkStream,
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator
    ) throws InterruptedException {
        while (true) {
            log.trace("Reading next chunk from TrafficStream supplier");
            if (stopReadingRef.get()) {
                break;
            }
            this.nextChunkFutureRef.set(
                trafficChunkStream.readNextTrafficStreamChunk(topLevelContext::createReadChunkContext)
            );
            List<ITrafficStreamWithKey> trafficStreams = null;
            try {
                trafficStreams = this.nextChunkFutureRef.get().get();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof EOFException) {
                    log.atWarn().setCause(ex.getCause())
                        .setMessage("Got an EOF on the stream.  " + "Done reading traffic streams.").log();
                    break;
                } else {
                    log.atWarn().setCause(ex).setMessage("Done reading traffic streams due to exception.").log();
                    throw ex.getCause();
                }
            }
            if (log.isDebugEnabled()) {
                Optional.of(
                    trafficStreams.stream()
                        .map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts.getStream()))
                        .collect(Collectors.joining(";"))
                )
                    .filter(s -> !s.isEmpty())
                    .ifPresent(s -> log.atDebug().setMessage("TrafficStream Summary: {{}}").addArgument(s).log());
            }
            log.atInfo().setMessage("Read {} traffic stream(s) from source")
                .addArgument(trafficStreams::size)
                .log();
            trafficStreams.forEach(trafficToHttpTransactionAccumulator::accept);
        }
    }
}
