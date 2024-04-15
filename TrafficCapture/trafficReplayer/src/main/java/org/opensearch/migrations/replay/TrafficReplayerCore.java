package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import java.io.EOFException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import java.util.stream.Stream;

@Slf4j
public abstract class TrafficReplayerCore {

    public interface IWorkTracker<T> {
        void put(UniqueReplayerRequestKey uniqueReplayerRequestKey,
                 DiagnosticTrackableCompletableFuture<String, T> completableFuture);
        void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey);
        boolean isEmpty();
        int size();
    }

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    protected final ClientConnectionPool clientConnectionPool;
    protected final TrafficStreamLimiter liveTrafficStreamLimiter;
    protected final AtomicInteger successfulRequestCount;
    protected final AtomicInteger exceptionRequestCount;
    public final IRootReplayerContext topLevelContext;
    protected final IWorkTracker<Void> requestWorkTracker;

    protected final AtomicBoolean stopReadingRef;
    protected final AtomicReference<CompletableFuture<List<ITrafficStreamWithKey>>> nextChunkFutureRef;

    protected TrafficReplayerCore(IRootReplayerContext context,
                                  URI serverUri,
                                  IAuthTransformerFactory authTransformer,
                                  IJsonTransformer jsonTransformer,
                                  ClientConnectionPool clientConnectionPool,
                                  TrafficStreamLimiter trafficStreamLimiter,
                                  IWorkTracker<Void> requestWorkTracker)
    {
        this.topLevelContext = context;
        if (serverUri.getPort() < 0) {
            throw new IllegalArgumentException("Port not present for URI: "+serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: "+serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: "+serverUri);
        }
        this.liveTrafficStreamLimiter = trafficStreamLimiter;
        this.clientConnectionPool = clientConnectionPool;
        this.requestWorkTracker = requestWorkTracker;
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformer, authTransformer);
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
        public Consumer<RequestResponsePacketPair>
        onRequestReceived(@NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                          @NonNull HttpMessageAndTimestamp request) {
            replayEngine.setFirstTimestamp(request.getFirstPacketTimestamp());

            var allWorkFinishedForTransaction =
                    new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                            ()->"waiting for " + ctx + " to be queued and run through TrafficStreamLimiter");
            var requestPushFuture = new StringTrackableCompletableFuture<TransformedTargetRequestAndResponse>(
                    new CompletableFuture<>(), () -> "Waiting to get response from target");
            var requestKey = ctx.getReplayerRequestKey();
            liveTrafficStreamLimiter.queueWork(1, ctx, wi ->
                transformAndSendRequest(replayEngine, request, ctx).future.whenComplete((v,t)->{
                    liveTrafficStreamLimiter.doneProcessing(wi);
                    if (t != null) {
                        requestPushFuture.future.completeExceptionally(t);
                    } else {
                        requestPushFuture.future.complete(v);
                    }
                }));
            if (!allWorkFinishedForTransaction.future.isDone()) {
                log.trace("Adding " + requestKey + " to targetTransactionInProgressMap");
                requestWorkTracker.put(requestKey, allWorkFinishedForTransaction);
                if (allWorkFinishedForTransaction.future.isDone()) {
                    requestWorkTracker.remove(requestKey);
                }
            }

            return rrPair ->
                    requestPushFuture.map(f -> f.handle((v, t) -> {
                                log.atInfo().setMessage(() -> "Done receiving captured stream for " + ctx +
                                        ":" + rrPair.requestData).log();
                                log.atTrace().setMessage(() ->
                                        "Summary response value for " + requestKey + " returned=" + v).log();
                                return handleCompletedTransaction(ctx, rrPair, v, t);
                            }), () -> "logging summary")
                            .whenComplete((v,t)->{
                                if (t != null) {
                                    allWorkFinishedForTransaction.future.completeExceptionally(t);
                                } else {
                                    allWorkFinishedForTransaction.future.complete(null);
                                }
                            }, ()->"");
        }

        Void handleCompletedTransaction(@NonNull IReplayContexts.IReplayerHttpTransactionContext context,
                                        RequestResponsePacketPair rrPair,
                                        TransformedTargetRequestAndResponse summary, Throwable t) {
            try (var httpContext = rrPair.getHttpTransactionContext()) {
                // if this comes in with a serious Throwable (not an Exception), don't bother
                // packaging it up and calling the callback.
                // Escalate it up out handling stack and shutdown.
                if (t == null || t instanceof Exception) {
                    try (var tupleHandlingContext = httpContext.createTupleContext()) {
                        packageAndWriteResponse(tupleHandlingContext, resultTupleConsumer,
                                rrPair, summary, (Exception) t);
                    }
                    commitTrafficStreams(rrPair.completionStatus, rrPair.trafficStreamKeysBeingHeld);
                    return null;
                } else {
                    log.atError().setCause(t).setMessage(() -> "Throwable passed to handle() for " + context +
                            ".  Rethrowing.").log();
                    throw Lombok.sneakyThrow(t);
                }
            } catch (Error error) {
                log.atError()
                        .setCause(error)
                        .setMessage(() -> "Caught error and initiating TrafficReplayer shutdown")
                        .log();
                shutdown(error);
                throw error;
            } catch (Exception e) {
                log.atError()
                        .setMessage("Unexpected exception while sending the " +
                                "aggregated response and context for {} to the callback.  " +
                                "Proceeding, but the tuple receiver context may be compromised.")
                        .addArgument(context)
                        .setCause(e)
                        .log();
                throw e;
            } finally {
                var requestKey = context.getReplayerRequestKey();
                requestWorkTracker.remove(requestKey);
                log.trace("removed rrPair.requestData to " +
                        "targetTransactionInProgressMap for " +
                        requestKey);
            }
        }

        @Override
        public void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                            @NonNull IReplayContexts.IChannelKeyContext ctx,
                                            @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            commitTrafficStreams(status, trafficStreamKeysBeingHeld);
        }

        @SneakyThrows
        private void commitTrafficStreams(RequestResponsePacketPair.ReconstructionStatus status,
                                          List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            commitTrafficStreams(status != RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
                    trafficStreamKeysBeingHeld);
        }

        @SneakyThrows
        private void commitTrafficStreams(boolean shouldCommit,
                                          List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            if (shouldCommit && trafficStreamKeysBeingHeld != null) {
                for (var tsk : trafficStreamKeysBeingHeld) {
                    tsk.getTrafficStreamsContext().close();
                    trafficCaptureSource.commitTrafficStream(tsk);
                }
            }
        }

        @Override
        public void onConnectionClose(int channelInteractionNum,
                                      @NonNull IReplayContexts.IChannelKeyContext ctx, int channelSessionNumber,
                                      RequestResponsePacketPair.ReconstructionStatus status,
                                      @NonNull Instant timestamp, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            replayEngine.setFirstTimestamp(timestamp);
            var cf = replayEngine.closeConnection(channelInteractionNum, ctx, channelSessionNumber, timestamp);
            cf.map(f->f.whenComplete((v,t) -> commitTrafficStreams(status, trafficStreamKeysBeingHeld)),
                    ()->"closing the channel in the ReplayEngine");
        }

        @Override
        public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
            commitTrafficStreams(true, List.of(ctx.getTrafficStreamKey()));
        }

        private void packageAndWriteResponse(IReplayContexts.ITupleHandlingContext tupleHandlingContext,
                                             Consumer<SourceTargetCaptureTuple> tupleWriter,
                                             RequestResponsePacketPair rrPair,
                                             TransformedTargetRequestAndResponse summary,
                                             Exception t) {
            log.trace("done sending and finalizing data to the packet handler");

            try (var requestResponseTuple = getSourceTargetCaptureTuple(tupleHandlingContext, rrPair, summary, t)) {
                log.atInfo().setMessage(()->"Source/Target Request/Response tuple: " + requestResponseTuple).log();
                tupleWriter.accept(requestResponseTuple);
            }

            if (t != null) { throw new CompletionException(t); }
            if (summary.getError() != null) {
                log.atInfo().setCause(summary.getError()).setMessage("Exception for {}: ")
                        .addArgument(tupleHandlingContext).log();
                exceptionRequestCount.incrementAndGet();
            } else if (summary.getTransformationStatus() == HttpRequestTransformationStatus.ERROR) {
                log.atInfo().setCause(summary.getError()).setMessage("Unknown error transforming {}: ")
                        .addArgument(tupleHandlingContext).log();
                exceptionRequestCount.incrementAndGet();
            } else {
                successfulRequestCount.incrementAndGet();
            }
        }
    }

    private static SourceTargetCaptureTuple
    getSourceTargetCaptureTuple(@NonNull IReplayContexts.ITupleHandlingContext tupleHandlingContext,
                                RequestResponsePacketPair rrPair,
                                TransformedTargetRequestAndResponse summary,
                                Exception t)
    {
        SourceTargetCaptureTuple requestResponseTuple;
        if (t != null) {
            log.error("Got exception in CompletableFuture callback: ", t);
            requestResponseTuple = new SourceTargetCaptureTuple(tupleHandlingContext, rrPair,
                    new TransformedPackets(), new ArrayList<>(),
                    HttpRequestTransformationStatus.ERROR, t, Duration.ZERO);
        } else {
            requestResponseTuple = new SourceTargetCaptureTuple(tupleHandlingContext, rrPair,
                    summary.requestPackets,
                    summary.getReceiptTimeAndResponsePackets()
                            .map(Map.Entry::getValue).collect(Collectors.toList()),
                    summary.getTransformationStatus(),
                    summary.getError(),
                    summary.getResponseDuration()
            );
        }
        return requestResponseTuple;
    }

    public DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(ReplayEngine replayEngine, HttpMessageAndTimestamp request,
                            IReplayContexts.IReplayerHttpTransactionContext ctx) {
        return transformAndSendRequest(inputRequestTransformerFactory, replayEngine, ctx,
                request.getFirstPacketTimestamp(), request.getLastPacketTimestamp(),
                request.packetBytes::stream);
    }

    public static DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
                            ReplayEngine replayEngine,
                            IReplayContexts.IReplayerHttpTransactionContext ctx,
                            @NonNull Instant start, @NonNull Instant end,
                            Supplier<Stream<byte[]>> packetsSupplier)
    {
        try {
            var transformationCompleteFuture = replayEngine.scheduleTransformationWork(ctx, start, ()->
                    transformAllData(inputRequestTransformerFactory.create(ctx), packetsSupplier));
            log.atDebug().setMessage(()->"finalizeRequest future for transformation of " + ctx +
                    " = " + transformationCompleteFuture).log();
            // It might be safer to chain this work directly inside the scheduleWork call above so that the
            // read buffer horizons aren't set after the transformation work finishes, but after the packets
            // are fully handled
            return transformationCompleteFuture.thenCompose(transformedResult ->
                                    replayEngine.scheduleRequest(ctx, start, end,
                                                    transformedResult.transformedOutput.size(),
                                                    transformedResult.transformedOutput.streamRetained())
                                            .map(future->future.thenApply(t ->
                                                            new TransformedTargetRequestAndResponse(transformedResult.transformedOutput,
                                                                    t, transformedResult.transformationStatus, t.error)),
                                                    ()->"(if applicable) packaging transformed result into a completed TransformedTargetRequestAndResponse object")
                                            .map(future->future.exceptionally(t ->
                                                            new TransformedTargetRequestAndResponse(transformedResult.transformedOutput,
                                                                    transformedResult.transformationStatus, t)),
                                                    ()->"(if applicable) packaging transformed result into a failed TransformedTargetRequestAndResponse object"),
                            () -> "transitioning transformed packets onto the wire")
                    .map(future->future.exceptionally(t->new TransformedTargetRequestAndResponse(null, null, t)),
                            ()->"Checking for exception out of sending data to the target server");
        } catch (Exception e) {
            log.debug("Caught exception in writeToSocket, so failing future");
            return StringTrackableCompletableFuture.failedFuture(e, ()->"TrafficReplayer.writeToSocketAndClose");
        }
    }

    private static <R> DiagnosticTrackableCompletableFuture<String, R>
    transformAllData(IPacketFinalizingConsumer<R> packetHandler, Supplier<Stream<byte[]>> packetSupplier) {
        try {
            var logLabel = packetHandler.getClass().getSimpleName();
            var packets = packetSupplier.get().map(Unpooled::wrappedBuffer);
            packets.forEach(packetData -> {
                log.atDebug().setMessage(() -> logLabel + " sending " + packetData.readableBytes() +
                        " bytes to the packetHandler").log();
                var consumeFuture = packetHandler.consumeBytes(packetData);
                log.atDebug().setMessage(() -> logLabel + " consumeFuture = " + consumeFuture).log();
            });
            log.atDebug().setMessage(() -> logLabel + "  done sending bytes, now finalizing the request").log();
            return packetHandler.finalizeRequest();
        } catch (Exception e) {
            log.atInfo().setCause(e).setMessage("Encountered an exception while transforming the http request.  " +
                    "The base64 gzipped traffic stream, for later diagnostic purposes, is: " +
                    Utils.packetsToCompressedTrafficStream(packetSupplier.get())).log();
            throw e;
        }
    }

    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
            ITrafficCaptureSource trafficChunkStream,
            CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator)
            throws InterruptedException {
        while (true) {
            log.trace("Reading next chunk from TrafficStream supplier");
            if (stopReadingRef.get()) {
                break;
            }
            this.nextChunkFutureRef.set(trafficChunkStream
                    .readNextTrafficStreamChunk(topLevelContext::createReadChunkContext));
            List<ITrafficStreamWithKey> trafficStreams = null;
            try {
                trafficStreams = this.nextChunkFutureRef.get().get();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof EOFException) {
                    log.atWarn().setCause(ex.getCause()).setMessage("Got an EOF on the stream.  " +
                            "Done reading traffic streams.").log();
                    break;
                } else {
                    log.atWarn().setCause(ex).setMessage("Done reading traffic streams due to exception.").log();
                    throw ex.getCause();
                }
            }
            if (log.isInfoEnabled()) {
                Optional.of(trafficStreams.stream()
                                .map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts.getStream()))
                                .collect(Collectors.joining(";")))
                        .filter(s -> !s.isEmpty())
                        .ifPresent(s -> log.atInfo().log("TrafficStream Summary: {" + s + "}"));
            }
            trafficStreams.forEach(trafficToHttpTransactionAccumulator::accept);
        }
    }
}
