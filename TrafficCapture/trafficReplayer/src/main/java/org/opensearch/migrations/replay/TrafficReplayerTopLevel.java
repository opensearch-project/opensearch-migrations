package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class TrafficReplayerTopLevel implements AutoCloseable {
    public static final String TARGET_CONNECTION_POOL_NAME = "targetConnectionPool";
    public static final int MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL = 10;

    public static AtomicInteger targetConnectionPoolUniqueCounter = new AtomicInteger();

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    private final ClientConnectionPool clientConnectionPool;
    private final TrafficStreamLimiter liveTrafficStreamLimiter;
    private final AtomicInteger successfulRequestCount;
    private final AtomicInteger exceptionRequestCount;
    public final IRootReplayerContext topLevelContext;
    private final ConcurrentHashMap<UniqueReplayerRequestKey,
            DiagnosticTrackableCompletableFuture<String, Void>> requestToFinalWorkFuturesMap;

    private final AtomicBoolean stopReadingRef;
    private final AtomicReference<StringTrackableCompletableFuture<Void>> allRemainingWorkFutureOrShutdownSignalRef;
    private final AtomicReference<Error> shutdownReasonRef;
    private final AtomicReference<CompletableFuture<Void>> shutdownFutureRef;
    private final AtomicReference<CompletableFuture<List<ITrafficStreamWithKey>>> nextChunkFutureRef;


    public TrafficReplayerTopLevel(IRootReplayerContext context,
                                   URI serverUri,
                                   IAuthTransformerFactory authTransformerFactory,
                                   boolean allowInsecureConnections)
            throws SSLException {
        this(context, serverUri, authTransformerFactory, allowInsecureConnections,
                new TransformationLoader().getTransformerFactoryLoader(serverUri.getHost()));
    }

    public TrafficReplayerTopLevel(IRootReplayerContext context,
                                   URI serverUri,
                                   IAuthTransformerFactory authTransformerFactory,
                                   boolean allowInsecureConnections,
                                   IJsonTransformer jsonTransformer)
            throws SSLException {
        this(context, serverUri, authTransformerFactory, allowInsecureConnections, 0,
                1024,
                jsonTransformer);
    }

    public TrafficReplayerTopLevel(IRootReplayerContext context,
                                   URI serverUri,
                                   IAuthTransformerFactory authTransformerFactory,
                                   boolean allowInsecureConnections,
                                   int numSendingThreads,
                                   int maxConcurrentOutstandingRequests,
                                   IJsonTransformer jsonTransformer)
            throws SSLException {
        this(context, serverUri, authTransformerFactory, allowInsecureConnections,
                numSendingThreads, maxConcurrentOutstandingRequests,
                jsonTransformer,
                getTargetConnectionPoolName(targetConnectionPoolUniqueCounter.getAndIncrement()));
    }

    private static String getTargetConnectionPoolName(int i) {
        return TARGET_CONNECTION_POOL_NAME + (i == 0 ? "" : Integer.toString(i));
    }

    public TrafficReplayerTopLevel(IRootReplayerContext context,
                                   URI serverUri,
                                   IAuthTransformerFactory authTransformer,
                                   boolean allowInsecureConnections,
                                   int numSendingThreads,
                                   int maxConcurrentOutstandingRequests,
                                   IJsonTransformer jsonTransformer,
                                   String clientThreadNamePrefix)
            throws SSLException
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
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformer, authTransformer);
        clientConnectionPool = new ClientConnectionPool(serverUri,
                loadSslContext(serverUri, allowInsecureConnections), clientThreadNamePrefix, numSendingThreads);
        requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        successfulRequestCount = new AtomicInteger();
        exceptionRequestCount = new AtomicInteger();
        liveTrafficStreamLimiter = new TrafficStreamLimiter(maxConcurrentOutstandingRequests);
        allRemainingWorkFutureOrShutdownSignalRef = new AtomicReference<>();
        shutdownReasonRef = new AtomicReference<>();
        shutdownFutureRef = new AtomicReference<>();
        nextChunkFutureRef = new AtomicReference<>();
        stopReadingRef = new AtomicBoolean();
    }

    private static SslContext loadSslContext(URI serverUri, boolean allowInsecureConnections) throws SSLException {
        if (serverUri.getScheme().equalsIgnoreCase("https")) {
            var sslContextBuilder = SslContextBuilder.forClient();
            if (allowInsecureConnections) {
                sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return sslContextBuilder.build();
        } else {
            return null;
        }
    }

    public void setupRunAndWaitForReplayToFinish(Duration observedPacketConnectionTimeout,
                                                 BlockingTrafficSource trafficSource,
                                                 TimeShifter timeShifter,
                                                 Consumer<SourceTargetCaptureTuple> resultTupleConsumer)
            throws InterruptedException, ExecutionException {

        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var replayEngine = new ReplayEngine(senderOrchestrator, trafficSource, timeShifter);

        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(observedPacketConnectionTimeout,
                        "(see command line option " +
                                TrafficReplayer.PACKET_TIMEOUT_SECONDS_PARAMETER_NAME + ")",
                        new TrafficReplayerAccumulationCallbacks(replayEngine, resultTupleConsumer, trafficSource));
        try {
            pullCaptureFromSourceToAccumulator(trafficSource, trafficToHttpTransactionAccumulator);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Terminating runReplay due to exception").log();
            throw e;
        } finally {
            trafficToHttpTransactionAccumulator.close();
            wrapUpWorkAndEmitSummary(replayEngine, trafficToHttpTransactionAccumulator);
            assert shutdownFutureRef.get() != null || requestToFinalWorkFuturesMap.isEmpty() :
                    "expected to wait for all the in flight requests to fully flush and self destruct themselves";
        }
    }

    protected void wrapUpWorkAndEmitSummary(ReplayEngine replayEngine,
                                            CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator)
            throws ExecutionException, InterruptedException {
        final var primaryLogLevel = Level.INFO;
        final var secondaryLogLevel = Level.WARN;
        var logLevel = primaryLogLevel;
        for (var timeout = Duration.ofSeconds(60); ; timeout = timeout.multipliedBy(2)) {
            if (shutdownFutureRef.get() != null) {
                log.warn("Not waiting for work because the TrafficReplayer is shutting down.");
                break;
            }
            try {
                waitForRemainingWork(logLevel, timeout);
                break;
            } catch (TimeoutException e) {
                log.atLevel(logLevel).log("Timed out while waiting for the remaining " +
                        "requests to be finalized...");
                logLevel = secondaryLogLevel;
            }
        }
        if (!requestToFinalWorkFuturesMap.isEmpty() || exceptionRequestCount.get() > 0) {
            log.atWarn().setMessage("{} in-flight requests being dropped due to pending shutdown; " +
                            "{} requests to the target threw an exception; " +
                            "{} requests were successfully processed.")
                    .addArgument(requestToFinalWorkFuturesMap.size())
                    .addArgument(exceptionRequestCount.get())
                    .addArgument(successfulRequestCount.get())
                    .log();
        } else {
            log.info(successfulRequestCount.get() + " requests were successfully processed.");
        }
        log.info("# of connections created: {}; # of requests on reused keep-alive connections: {}; " +
                        "# of expired connections: {}; # of connections closed: {}; " +
                        "# of connections terminated upon accumulator termination: {}",
                trafficToHttpTransactionAccumulator.numberOfConnectionsCreated(),
                trafficToHttpTransactionAccumulator.numberOfRequestsOnReusedConnections(),
                trafficToHttpTransactionAccumulator.numberOfConnectionsExpired(),
                trafficToHttpTransactionAccumulator.numberOfConnectionsClosed(),
                trafficToHttpTransactionAccumulator.numberOfRequestsTerminatedUponAccumulatorClose()
        );
    }

    public void setupRunAndWaitForReplayWithShutdownChecks(Duration observedPacketConnectionTimeout,
                                                           BlockingTrafficSource trafficSource,
                                                           TimeShifter timeShifter,
                                                           Consumer<SourceTargetCaptureTuple> resultTupleConsumer)
            throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        try {
            setupRunAndWaitForReplayToFinish(observedPacketConnectionTimeout, trafficSource,
                    timeShifter, resultTupleConsumer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrafficReplayer.TerminationException(shutdownReasonRef.get(), e);
        } catch (Throwable t) {
            throw new TrafficReplayer.TerminationException(shutdownReasonRef.get(), t);
        }
        if (shutdownReasonRef.get() != null) {
            throw new TrafficReplayer.TerminationException(shutdownReasonRef.get(), null);
        }
        // if nobody has run shutdown yet, do so now so that we can tear down the netty resources
        shutdown(null).get(); // if somebody already HAD run shutdown, it will return the future already created
    }

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
                            ()->"waiting for work to be queued and run through TrafficStreamLimiter");
            var requestPushFuture = new StringTrackableCompletableFuture<TransformedTargetRequestAndResponse>(
                    new CompletableFuture<>(), () -> "Waiting to get response from target");
            var requestKey = ctx.getReplayerRequestKey();
            liveTrafficStreamLimiter.queueWork(1, ctx, wi -> {
                transformAndSendRequest(replayEngine, request, ctx).future.whenComplete((v,t)->{
                    liveTrafficStreamLimiter.doneProcessing(wi);
                    if (t != null) {
                        requestPushFuture.future.completeExceptionally(t);
                    } else {
                        requestPushFuture.future.complete(v);
                    }
                });
            });
            if (!allWorkFinishedForTransaction.future.isDone()) {
                log.trace("Adding " + requestKey + " to targetTransactionInProgressMap");
                requestToFinalWorkFuturesMap.put(requestKey, allWorkFinishedForTransaction);
                if (allWorkFinishedForTransaction.future.isDone()) {
                    requestToFinalWorkFuturesMap.remove(requestKey);
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
                requestToFinalWorkFuturesMap.remove(requestKey);
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
            cf.map(f->f.whenComplete((v,t)->{
                commitTrafficStreams(status, trafficStreamKeysBeingHeld);
            }), ()->"closing the channel in the ReplayEngine");
        }

        @Override
        public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
            commitTrafficStreams(true, List.of(ctx.getTrafficStreamKey()));
        }

        private TransformedTargetRequestAndResponse
        packageAndWriteResponse(IReplayContexts.ITupleHandlingContext tupleHandlingContext,
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
            return summary;
        }
    }

    protected void waitForRemainingWork(Level logLevel, @NonNull Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (!liveTrafficStreamLimiter.isStopped()) {
            var streamLimiterHasRunEverything = new CompletableFuture<Void>();
            liveTrafficStreamLimiter.queueWork(1, null, wi -> {
                streamLimiterHasRunEverything.complete(null);
                liveTrafficStreamLimiter.doneProcessing(wi);
            });
            streamLimiterHasRunEverything.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Map.Entry<UniqueReplayerRequestKey, DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>>[]
                allRemainingWorkArray = requestToFinalWorkFuturesMap.entrySet().toArray(Map.Entry[]::new);
        writeStatusLogsForRemainingWork(logLevel, allRemainingWorkArray);

        // remember, this block is ONLY for the leftover items.  Lots of other items have been processed
        // and were removed from the live map (hopefully)
        DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>[] allCompletableFuturesArray =
                Arrays.stream(allRemainingWorkArray)
                        .map(Map.Entry::getValue).toArray(DiagnosticTrackableCompletableFuture[]::new);
        var allWorkFuture = StringTrackableCompletableFuture.allOf(allCompletableFuturesArray,
                () -> "TrafficReplayer.AllWorkFinished");
        try {
            if (allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, allWorkFuture)) {
                allWorkFuture.get(timeout);
            } else {
                handleAlreadySetFinishedSignal();
            }
        } catch (TimeoutException e) {
            var didCancel = allWorkFuture.future.cancel(true);
            if (!didCancel) {
                assert allWorkFuture.future.isDone() : "expected future to have finished if cancel didn't succeed";
                // continue with the rest of the function
            } else {
                throw e;
            }
        } finally {
            allRemainingWorkFutureOrShutdownSignalRef.set(null);
        }
    }

    private void handleAlreadySetFinishedSignal() throws InterruptedException, ExecutionException {
        try {
            var finishedSignal = allRemainingWorkFutureOrShutdownSignalRef.get().future;
            assert finishedSignal.isDone() : "Expected this reference to be EITHER the current work futures " +
                    "or a sentinel value indicating a shutdown has commenced.  The signal, when set, should " +
                    "have been completed at the time that the reference was set";
            finishedSignal.get();
            log.debug("Did shutdown cleanly");
        } catch (ExecutionException e) {
            var c = e.getCause();
            if (c instanceof Error) {
                throw (Error) c;
            } else {
                throw e;
            }
        } catch (Error t) {
            log.atError().setCause(t).setMessage(() -> "Not waiting for all work to finish.  " +
                    "The TrafficReplayer is shutting down").log();
            throw t;
        }
    }

    private static void writeStatusLogsForRemainingWork(Level logLevel,
                                                        Map.Entry<UniqueReplayerRequestKey,
                                                                DiagnosticTrackableCompletableFuture<String,
                                                                        TransformedTargetRequestAndResponse>>[]
                                                                allRemainingWorkArray) {
        log.atLevel(logLevel).log("All remaining work to wait on " + allRemainingWorkArray.length);
        if (log.isInfoEnabled()) {
            LoggingEventBuilder loggingEventBuilderToUse = log.isTraceEnabled() ? log.atTrace() : log.atInfo();
            long itemLimit = log.isTraceEnabled() ? Long.MAX_VALUE : MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL;
            loggingEventBuilderToUse.setMessage(() -> " items: " +
                            Arrays.stream(allRemainingWorkArray)
                                    .map(kvp -> kvp.getKey() + " --> " +
                                            kvp.getValue().formatAsString(TrafficReplayerTopLevel::formatWorkItem))
                                    .limit(itemLimit)
                                    .collect(Collectors.joining("\n")))
                    .log();
        }
    }

    private static String formatWorkItem(DiagnosticTrackableCompletableFuture<String,?> cf) {
        try {
            var resultValue = cf.get();
            if (resultValue instanceof TransformedTargetRequestAndResponse) {
                return "" + ((TransformedTargetRequestAndResponse) resultValue).getTransformationStatus();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Exception: " + e.getMessage();
        } catch (ExecutionException e) {
            return e.getMessage();
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
    public @NonNull CompletableFuture<Void> shutdown(Error error) {
        log.atWarn().setCause(error).setMessage(()->"Shutting down " + this).log();
        shutdownReasonRef.compareAndSet(null, error);
        if (!shutdownFutureRef.compareAndSet(null, new CompletableFuture<>())) {
            log.atError().setMessage(()->"Shutdown was already signaled by {}.  " +
                            "Ignoring this shutdown request due to {}.")
                    .addArgument(shutdownReasonRef.get())
                    .addArgument(error)
                    .log();
            return shutdownFutureRef.get();
        }
        stopReadingRef.set(true);
        liveTrafficStreamLimiter.close();

        var nettyShutdownFuture = clientConnectionPool.shutdownNow();
        nettyShutdownFuture.whenComplete((v,t) -> {
            if (t != null) {
                shutdownFutureRef.get().completeExceptionally(t);
            } else {
                shutdownFutureRef.get().complete(null);
            }
        });
        Optional.ofNullable(this.nextChunkFutureRef.get()).ifPresent(f->f.cancel(true));
        var shutdownWasSignalledFuture = error == null ?
                StringTrackableCompletableFuture.<Void>completedFuture(null, ()->"TrafficReplayer shutdown") :
                StringTrackableCompletableFuture.<Void>failedFuture(error, ()->"TrafficReplayer shutdown");
        while (!allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, shutdownWasSignalledFuture)) {
            var otherRemainingWorkObj = allRemainingWorkFutureOrShutdownSignalRef.get();
            if (otherRemainingWorkObj != null) {
                otherRemainingWorkObj.future.cancel(true);
                break;
            }
        }
        var shutdownFuture = shutdownFutureRef.get();
        log.atWarn().setMessage(()->"Shutdown setup has been initiated").log();
        return shutdownFuture;
    }


    @Override
    public void close() throws Exception {
        shutdown(null).get();
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
