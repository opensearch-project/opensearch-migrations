package org.opensearch.migrations.replay;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.http.retries.RetryCollectingVisitorFactory;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.RecordWorkTracker;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeOwner;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.lifecycle.RequestLifecycleInput;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

@Slf4j
public class TrafficReplayerTopLevel extends TrafficReplayerCore implements AutoCloseable {
    public static final String TARGET_CONNECTION_POOL_NAME = "targetConnectionPool";
    public static final int MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL = 10;
    private static final Duration SHUTDOWN_PROGRESS_REPORT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration ACTOR_TERMINATION_SHUTDOWN_LIMIT = Duration.ofMinutes(2);
    private static final Duration REMAINING_WORK_SHUTDOWN_LIMIT = Duration.ofMinutes(2);

    private final AtomicReference<CapturedTrafficToHttpTransactionAccumulator> currentAccumulator = new AtomicReference<>();
    private final AtomicReference<ReplayEngine> currentReplayEngine = new AtomicReference<>();

    /** Returns the current accumulator, or null if not yet initialized. */
    public CapturedTrafficToHttpTransactionAccumulator getCurrentAccumulator() {
        return currentAccumulator.get();
    }

    /** Returns the current replay engine, or null if not yet initialized. */
    public ReplayEngine getCurrentReplayEngine() {
        return currentReplayEngine.get();
    }

    static TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink(
        @NonNull Function<RequestLifecycleInput, CompletionStage<Void>> submitter
    ) {
        return new TargetConnectionOwner.RequestLifecycleSink() {
            @Override
            public CompletionStage<Void> connectionRequestFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return submitter.apply(
                    new RequestLifecycleInput.ConnectionRequestFinished(
                        partitionGenerationId,
                        requestId
                    )
                );
            }

            @Override
            public CompletionStage<Void> requestProcessingFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return submitter.apply(
                    new RequestLifecycleInput.RequestProcessingFinished(
                        partitionGenerationId,
                        requestId
                    )
                );
            }
        };
    }

    public interface IStreamableWorkTracker<T> extends IWorkTracker<T> {
        public Stream<Map.Entry<UniqueReplayerRequestKey, TrackedFuture<String, T>>> getRemainingItems();
    }

    static class ConcurrentHashMapWorkTracker<T> implements IStreamableWorkTracker<T> {
        ConcurrentHashMap<UniqueReplayerRequestKey, TrackedFuture<String, T>> map = new ConcurrentHashMap<>();

        @Override
        public void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, TrackedFuture<String, T> completableFuture) {
            map.put(uniqueReplayerRequestKey, completableFuture);
        }

        @Override
        public void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey) {
            map.remove(uniqueReplayerRequestKey);
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public int size() {
            return map.size();
        }

        public Stream<Map.Entry<UniqueReplayerRequestKey, TrackedFuture<String, T>>> getRemainingItems() {
            return map.entrySet().stream();
        }
    }

    private final AtomicReference<TextTrackedFuture<Void>> allRemainingWorkFutureOrShutdownSignalRef;
    protected final ClientConnectionPool clientConnectionPool;
    private final AtomicReference<Error> shutdownReasonRef;
    private final AtomicReference<CompletableFuture<Void>> shutdownFutureRef;
    private final AtomicBoolean fatalShutdownClaimed;
    private final ReplayProcessFatalHandler.ProcessTerminator fatalProcessTerminator;

    public TrafficReplayerTopLevel(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentTargetAttempts,
        IStreamableWorkTracker<Void> workTracker,
        BulkItemErrorClassifier errorClassifier,
        ExceptionTypeAllowlist poisonAllowlist,
        ReplayProcessFatalHandler.ProcessTerminator fatalProcessTerminator
    ) {
        super(
            context,
            serverUri,
            authTransformerFactory,
            jsonTransformerSupplier,
            maxConcurrentTargetAttempts,
            workTracker,
            new RetryCollectingVisitorFactory(new OpenSearchDefaultRetry(errorClassifier)),
            new TargetResponseClassifier(errorClassifier, poisonAllowlist)
        );
        this.clientConnectionPool = clientConnectionPool;
        allRemainingWorkFutureOrShutdownSignalRef = new AtomicReference<>();
        shutdownReasonRef = new AtomicReference<>();
        shutdownFutureRef = new AtomicReference<>();
        fatalShutdownClaimed = new AtomicBoolean();
        this.fatalProcessTerminator = Objects.requireNonNull(fatalProcessTerminator);
    }


    public static ClientConnectionPool
    makeNettyPacketConsumerConnectionPool(URI serverUri, boolean allowInsecureConnections, int numSendingThreads) {
        return makeNettyPacketConsumerConnectionPool(
            serverUri,
            allowInsecureConnections,
            numSendingThreads,
            null,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(
        URI serverUri,
        boolean allowInsecureConnections,
        int numSendingThreads,
        String connectionPoolName
    ) {
        return makeNettyPacketConsumerConnectionPool(
            serverUri,
            allowInsecureConnections,
            numSendingThreads,
            connectionPoolName,
            TargetExchangeState.Metrics.NOOP
        );
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(
        URI serverUri,
        boolean allowInsecureConnections,
        int numSendingThreads,
        String connectionPoolName,
        TargetExchangeState.Metrics metrics
    ) {
        return new ClientConnectionPool(
            NettyPacketToHttpConsumer.createClientConnectionFactory(
                loadSslContext(serverUri, allowInsecureConnections), serverUri),
            connectionPoolName != null
                ? connectionPoolName
                : TARGET_CONNECTION_POOL_NAME,
            numSendingThreads,
            metrics
        );
    }

    @SneakyThrows
    public static SslContext loadSslContext(URI serverUri, boolean allowInsecureConnections) {
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

    public void setupRunAndWaitForReplayToFinish(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Duration quiescentDuration
    ) throws InterruptedException, ExecutionException {
        setupRunAndWaitForReplayToFinish(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, tupleWriter, null, quiescentDuration);
    }

    /** Legacy overload: uses the old synchronous Consumer-based tuple path (Log4J). */
    public void setupRunAndWaitForReplayToFinish(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Duration quiescentDuration
    ) throws InterruptedException, ExecutionException {
        doSetupRunAndWaitForReplayToFinish(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, null, resultTupleConsumer, null, quiescentDuration);
    }

    public void setupRunAndWaitForReplayToFinish(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Consumer<SourceTargetCaptureTuple> tupleObserver,
        Duration quiescentDuration
    ) throws InterruptedException, ExecutionException {
        doSetupRunAndWaitForReplayToFinish(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, tupleWriter, null, tupleObserver, quiescentDuration);
    }

    private void doSetupRunAndWaitForReplayToFinish(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Consumer<SourceTargetCaptureTuple> tupleObserver,
        Duration quiescentDuration
    ) throws InterruptedException, ExecutionException {
        var fatalHandler = new ReplayProcessFatalHandler(
            failure -> failure instanceof RequestSenderOrchestrator.EventLoopTerminatedError
                ? ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED
                : ReplayProcessFatalHandler.Reason.UNEXPECTED_FATAL_ERROR,
            topLevelContext.getReplayProcessFatalMetrics(),
            fatalProcessTerminator,
            org.apache.logging.log4j.LogManager::shutdown,
            System.err,
            failure -> shutdown(failure)
        );
        var replayIntakeOwner = new ReplayIntakeOwner(fatalHandler::onFatal);
        var permitPool = new TargetAttemptPermitProvider(
            maxConcurrentTargetAttempts,
            replayIntakeOwner::submitRequired,
            topLevelContext.getPermitPoolMetrics()
        );
        this.intakeOwner = replayIntakeOwner;
        var requestLifecycleSink = requestLifecycleSink(
            replayIntakeOwner::submitRequiredHandled
        );
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            permitPool,
            (replaySession, ctx, firstTargetWriteSubmitted) -> new NettyPacketToHttpConsumer(
                replaySession,
                ctx,
                targetServerResponseTimeout,
                firstTargetWriteSubmitted
            ),
            trafficSource::acknowledgeSessionTermination,
            topLevelContext.getConnectionActorMetrics(),
            topLevelContext.getTargetExchangeStateMetrics(),
            topLevelContext.getResourceOwnershipMetrics(),
            requestLifecycleSink,
            fatalHandler
        );
        var readGate = new ReplayReadGate(trafficSource.getBufferTimeWindow(), trafficSource);
        var progressController = new ReplayProgressController(replayIntakeOwner::submitRequired, readGate);
        var replayEngine = new ReplayEngine(
            senderOrchestrator,
            trafficSource,
            timeShifter,
            progressController
        );
        this.currentReplayEngine.set(replayEngine);
        var recordWorkTracker = new RecordWorkTracker(
            replayIntakeOwner::submitRequired,
            replayIntakeOwner::isOwnerThread,
            recordId -> trafficSource.recordProcessingFinished(recordId)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        fatalHandler.onFatal(new Error(
                            "Kafka source rejected required record-processing completion " + recordId,
                            failure
                        ));
                    }
                })
        );
        var accumulationCallbacks = new TrafficReplayerAccumulationCallbacks(
            replayEngine,
            tupleWriter,
            resultTupleConsumer,
            tupleObserver,
            trafficSource,
            quiescentDuration,
            recordWorkTracker
        );
        trafficSource.setSourcePartitionLifecycleListener(
            SourcePartitionLifecycleListener.combine(
                progressController,
                accumulationCallbacks.sourcePartitionLifecycleListener()
            )
        );
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
            new CapturedTrafficToHttpTransactionAccumulator(
                observedPacketConnectionTimeout,
                "(see command line option " + TrafficReplayer.PACKET_TIMEOUT_SECONDS_PARAMETER_NAME + ")",
                accumulationCallbacks,
                trafficSource.usesStructuralExpiration(),
                recordWorkTracker,
                trafficSource::recordIdFor
            );
        this.currentAccumulator.set(trafficToHttpTransactionAccumulator);
        replayIntakeOwner.configureOwnedComponents(
            permitPool,
            progressController,
            recordWorkTracker
        );
        replayIntakeOwner.start();
        Throwable primaryFailure = null;
        var readingCompletion = replayIntakeOwner.startReading(
            trafficSource,
            trafficToHttpTransactionAccumulator,
            topLevelContext::createReadChunkContext
        ).toCompletableFuture();
        try {
            readingCompletion.get();
        } catch (Throwable failure) {
            primaryFailure = failure;
            if (!(failure instanceof InterruptedException)) {
                log.atWarn().setCause(failure).setMessage("Terminating runReplay due to exception").log();
            }
        }
        finishIntakeLifecycle(
            replayIntakeOwner,
            readingCompletion,
            primaryFailure,
            () -> wrapUpWorkAndEmitSummary(replayEngine, trafficToHttpTransactionAccumulator)
        );
    }

    /**
     * Called after the TrafficReplayer has finished accumulating and reconstructing every transaction from
     * the incoming stream.  This implementation will NOT wait for the ReplayEngine independently to complete,
     * but rather call waitForRemainingWork.  If a subclass wants more details  from either of the two main
     * non-field components of a TrafficReplayer, they have access to each of them here.
     *
     * @param replayEngine The ReplayEngine that may still be working to send the accumulated requests.
     * @param trafficToHttpTransactionAccumulator The accumulator that had reconstructed the incoming records and
     *                                            has now finished
     */
    protected void wrapUpWorkAndEmitSummary(
        ReplayEngine replayEngine,
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator
    ) throws ExecutionException, InterruptedException {
        final var primaryLogLevel = Level.INFO;
        final var secondaryLogLevel = Level.WARN;
        if (shutdownFutureRef.get() != null) {
            log.warn("Not waiting for work because the TrafficReplayer is shutting down.");
        } else {
            try {
                waitForRemainingWork(primaryLogLevel, REMAINING_WORK_SHUTDOWN_LIMIT);
            } catch (TimeoutException e) {
                log.atLevel(secondaryLogLevel)
                    .log("Timed out while waiting for the remaining requests to be finalized...");
            }
        }
        if (!requestWorkTracker.isEmpty() || exceptionRequestCount.get() > 0) {
            log.atWarn()
                .setMessage("{} in-flight requests being dropped due to pending shutdown; "
                    + "{} requests to the target threw an exception; "
                    + "{} requests were successfully processed.")
                .addArgument(requestWorkTracker::size)
                .addArgument(exceptionRequestCount::get)
                .addArgument(successfulRequestCount::get)
                .log();
        } else {
            log.info(successfulRequestCount.get() + " requests were successfully processed.");
        }
        log.info(
            "# of connections created: {}; # of requests on reused keep-alive connections: {}; "
                + "# of expired connections: {}; # of connections closed: {}; "
                + "# of connections terminated upon accumulator termination: {}",
            trafficToHttpTransactionAccumulator.numberOfConnectionsCreated(),
            trafficToHttpTransactionAccumulator.numberOfRequestsOnReusedConnections(),
            trafficToHttpTransactionAccumulator.numberOfConnectionsExpired(),
            trafficToHttpTransactionAccumulator.numberOfConnectionsClosed(),
            trafficToHttpTransactionAccumulator.numberOfRequestsTerminatedUponAccumulatorClose()
        );
    }

    public void setupRunAndWaitForReplayWithShutdownChecks(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Duration quiescentDuration
    ) throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        setupRunAndWaitForReplayWithShutdownChecks(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, tupleWriter, null, quiescentDuration);
    }

    /** Legacy overload: uses the old synchronous Consumer-based tuple path (Log4J). */
    public void setupRunAndWaitForReplayWithShutdownChecks(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Duration quiescentDuration
    ) throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        doSetupRunAndWaitForReplayWithShutdownChecks(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, null, resultTupleConsumer, null, quiescentDuration);
    }

    public void setupRunAndWaitForReplayWithShutdownChecks(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Consumer<SourceTargetCaptureTuple> tupleObserver,
        Duration quiescentDuration
    ) throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        doSetupRunAndWaitForReplayWithShutdownChecks(observedPacketConnectionTimeout, targetServerResponseTimeout,
            trafficSource, timeShifter, tupleWriter, null, tupleObserver, quiescentDuration);
    }

    private void doSetupRunAndWaitForReplayWithShutdownChecks(
        Duration observedPacketConnectionTimeout,
        Duration targetServerResponseTimeout,
        BlockingTrafficSource trafficSource,
        TimeShifter timeShifter,
        ThreadLocalTupleWriter tupleWriter,
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Consumer<SourceTargetCaptureTuple> tupleObserver,
        Duration quiescentDuration
    ) throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        try {
            doSetupRunAndWaitForReplayToFinish(
                observedPacketConnectionTimeout,
                targetServerResponseTimeout,
                trafficSource,
                timeShifter,
                tupleWriter,
                resultTupleConsumer,
                tupleObserver,
                quiescentDuration
            );
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

    protected void waitForRemainingWork(Level logLevel, @NonNull Duration timeout) throws ExecutionException,
        InterruptedException, TimeoutException {

        var workTracker = (IStreamableWorkTracker<Void>) requestWorkTracker;
        Map.Entry<
            UniqueReplayerRequestKey,
            TrackedFuture<String, TransformedTargetRequestAndResponseList>>[] allRemainingWorkArray = workTracker
                .getRemainingItems()
                .toArray(Map.Entry[]::new);
        writeStatusLogsForRemainingWork(logLevel, allRemainingWorkArray);

        // remember, this block is ONLY for the leftover items. Lots of other items have been processed
        // and were removed from the live map (hopefully)
        TrackedFuture<String, TransformedTargetRequestAndResponseList>[] allCompletableFuturesArray = Arrays.stream(
            allRemainingWorkArray
        ).map(Map.Entry::getValue).toArray(TrackedFuture[]::new);
        var requestWorkFuture = TextTrackedFuture.allOf(
            allCompletableFuturesArray,
            () -> "TrafficReplayer.AllRequestsFinished"
        );
        var replayEngine = currentReplayEngine.get();
        var replayQuiescenceFuture = replayEngine == null
            ? CompletableFuture.<Void>completedFuture(null)
            : replayEngine.whenQuiescent().toCompletableFuture();
        var allWorkFuture = new TextTrackedFuture<>(
            combineReplayDrainGates(
                requestWorkFuture.future,
                replayQuiescenceFuture
            ),
            () -> "TrafficReplayer.AllWorkFinished"
        );
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

    static CompletableFuture<Void> combineReplayDrainGates(
        CompletionStage<Void> requestWork,
        CompletionStage<Void> replayQuiescence
    ) {
        return CompletableFuture.allOf(
            requestWork.toCompletableFuture(),
            replayQuiescence.toCompletableFuture()
        );
    }

    private void handleAlreadySetFinishedSignal() throws InterruptedException, ExecutionException {
        try {
            var finishedSignal = allRemainingWorkFutureOrShutdownSignalRef.get().future;
            assert finishedSignal.isDone() : "Expected this reference to be EITHER the current work futures "
                + "or a sentinel value indicating a shutdown has commenced.  The signal, when set, should "
                + "have been completed at the time that the reference was set";
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
            log.atError().setCause(t)
                .setMessage("Not waiting for all work to finish.  The TrafficReplayer is shutting down").log();
            throw t;
        }
    }

    protected static void writeStatusLogsForRemainingWork(
        Level logLevel,
        Map.Entry<
            UniqueReplayerRequestKey,
            TrackedFuture<String, TransformedTargetRequestAndResponseList>>[] allRemainingWorkArray
    ) {
        log.atLevel(logLevel).setMessage("All remaining work to wait on {}")
            .addArgument(allRemainingWorkArray.length).log();
        if (log.isInfoEnabled()) {
            LoggingEventBuilder loggingEventBuilderToUse = log.isTraceEnabled() ? log.atTrace() : log.atInfo();
            long itemLimit = log.isTraceEnabled() ? Long.MAX_VALUE : MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL;
            loggingEventBuilderToUse.setMessage(" items: {}")
                .addArgument(() -> Arrays.stream(allRemainingWorkArray)
                    .map(
                        kvp -> kvp.getKey()
                            + " --> "
                            + kvp.getValue().formatAsString(TrafficReplayerTopLevel::formatWorkItem)
                    )
                    .limit(itemLimit)
                    .collect(Collectors.joining("\n")))
            .log();
        }
    }

    static String formatWorkItem(TrackedFuture<String, ?> cf) {
        try {
            var resultValue = cf.get();
            if (resultValue instanceof TransformedTargetRequestAndResponseList) {
                return "" + ((TransformedTargetRequestAndResponseList) resultValue).getTransformationStatus();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Exception: " + e.getMessage();
        } catch (ExecutionException e) {
            return e.getMessage();
        }
    }

    @SneakyThrows
    @Override
    public @NonNull CompletableFuture<Void> shutdown(Error error) {
        log.atWarn().setCause(error).setMessage("Shutting down {}").addArgument(this).log();
        if (error != null) {
            fatalShutdownClaimed.set(true);
            shutdownReasonRef.compareAndSet(null, error);
        }
        var existingShutdown = claimShutdown(error);
        if (existingShutdown != null) {
            if (error != null) {
                var lateFatalCancellation = new CancellationException(
                    "fatal replay shutdown superseded orderly shutdown"
                );
                var replayIntakeOwner = intakeOwner;
                if (replayIntakeOwner != null) {
                    replayIntakeOwner.stopReading();
                    replayIntakeOwner.closePermits(lateFatalCancellation);
                }
                signalRemainingWorkShutdown(error);
                existingShutdown.completeExceptionally(error);
            }
            return existingShutdown;
        }
        var cancellationCause = new CancellationException("replay is shutting down");
        var replayEngine = currentReplayEngine.get();
        var replayIntakeOwner = intakeOwner;
        if (error != null) {
            if (replayIntakeOwner != null) {
                replayIntakeOwner.stopReading();
                replayIntakeOwner.closePermits(cancellationCause);
            }
            signalRemainingWorkShutdown(error);
            shutdownFutureRef.get().completeExceptionally(error);
            log.atError()
                .setCause(error)
                .setMessage("Fatal shutdown was signaled without waiting for owner-confined work")
                .log();
            return shutdownFutureRef.get();
        }

        // Normal shutdown gives every actor a bounded opportunity to settle before releasing Netty's
        // event loops. Reaching this bound while a session is still live is a process-fatal ownership
        // failure: event-loop termination closes commit admission, flushes fatal diagnostics, and halts
        // the process as documented in section 16.3.
        var actorShutdownFuture = beginReplayShutdownAfterIntakeFence(replayEngine, cancellationCause)
            .copy()
            .orTimeout(ACTOR_TERMINATION_SHUTDOWN_LIMIT.toSeconds(), TimeUnit.SECONDS);
        completeShutdownFrom(shutdownNettyAfterActors(actorShutdownFuture));
        signalRemainingWorkShutdown(error);
        var shutdownFuture = shutdownFutureRef.get();
        log.atWarn().setMessage("Shutdown setup has been initiated").log();
        return shutdownFuture;
    }

    private CompletableFuture<Void> claimShutdown(Error error) {
        if (shutdownFutureRef.compareAndSet(null, new CompletableFuture<>())) {
            return null;
        }
        log.atError().setMessage("Shutdown was already signaled by {}.  Ignoring this shutdown request due to {}.")
            .addArgument(shutdownReasonRef::get)
            .addArgument(error)
            .log();
        return shutdownFutureRef.get();
    }

    private CompletableFuture<Void> shutdownNettyAfterActors(CompletableFuture<Void> actorShutdownFuture) {
        return actorShutdownFuture
            .handle((ignored, actorFailure) -> actorFailure)
            .thenCompose(actorFailure ->
                clientConnectionPool.shutdownNow()
                    .handle((ignored, nettyFailure) -> combineShutdownFailures(actorFailure, nettyFailure))
            );
    }

    private static Void combineShutdownFailures(Throwable actorFailure, Throwable nettyFailure) {
        if (actorFailure != null) {
            if (nettyFailure != null) {
                actorFailure.addSuppressed(nettyFailure);
            }
            throw new CompletionException(actorFailure);
        }
        if (nettyFailure != null) {
            throw new CompletionException(nettyFailure);
        }
        return null;
    }

    private void completeShutdownFrom(CompletableFuture<Void> nettyShutdownFuture) {
        nettyShutdownFuture.whenComplete((ignored, failure) -> {
            if (failure != null) {
                shutdownFutureRef.get().completeExceptionally(failure);
            } else {
                shutdownFutureRef.get().complete(null);
            }
        });
    }

    private void signalRemainingWorkShutdown(Error error) {
        var shutdownWasSignalledFuture = error == null
            ? TextTrackedFuture.<Void>completedFuture(null, () -> "TrafficReplayer shutdown")
            : TextTrackedFuture.<Void>failedFuture(error, () -> "TrafficReplayer shutdown");
        while (!allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, shutdownWasSignalledFuture)) {
            var otherRemainingWorkObj = allRemainingWorkFutureOrShutdownSignalRef.get();
            if (otherRemainingWorkObj != null) {
                otherRemainingWorkObj.future.cancel(true);
                break;
            }
        }
    }

    private CompletableFuture<Void> beginReplayShutdownAfterIntakeFence(
        ReplayEngine replayEngine,
        CancellationException cause
    ) {
        var completion = new CompletableFuture<Void>();
        var stage = new AtomicReference<>(ShutdownStage.TERMINATING_CONNECTION_ACTORS);
        var replayIntakeOwner = intakeOwner;
        Runnable beginShutdown = () -> {
            var fatalReason = shutdownReasonRef.get();
            if (fatalShutdownClaimed.get() && fatalReason != null) {
                stage.set(ShutdownStage.DONE);
                completion.completeExceptionally(fatalReason);
                return;
            }
            try {
                var actorShutdown = replayEngine == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : replayEngine.shutdownConnections(cause);
                var permitShutdown = replayIntakeOwner == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : replayIntakeOwner.closePermits(cause);
                CompletableFuture.allOf(
                    actorShutdown.toCompletableFuture(),
                    permitShutdown.toCompletableFuture()
                ).whenComplete((ignored, failure) -> {
                        stage.set(ShutdownStage.DONE);
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    });
            } catch (Throwable t) {
                stage.set(ShutdownStage.DONE);
                completion.completeExceptionally(t);
            }
        };
        reportShutdownProgressUntilComplete(stage, completion, replayEngine);
        var intakeFence = replayIntakeOwner == null
            ? CompletableFuture.<Void>completedFuture(null)
            : replayIntakeOwner.stopReading()
                .thenCompose(ignored -> replayIntakeOwner.fence())
                .toCompletableFuture();
        intakeFence.whenComplete((ignored, failure) -> {
            if (failure != null) {
                stage.set(ShutdownStage.DONE);
                completion.completeExceptionally(failure);
            } else {
                beginShutdown.run();
            }
        });
        return completion;
    }

    private enum ShutdownStage {
        TERMINATING_CONNECTION_ACTORS,
        DONE
    }

    /**
     * A shutdown that cannot finish is otherwise silent, which leaves nothing to diagnose from. Name
     * the stage that hasn't finished along with the work it is still waiting on.
     */
    private void reportShutdownProgressUntilComplete(
        AtomicReference<ShutdownStage> stage,
        CompletableFuture<Void> completion,
        ReplayEngine replayEngine
    ) {
        if (completion.isDone()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (completion.isDone()) {
                return;
            }
            var unterminatedSessions = replayEngine == null
                ? Map.<Object, String>of().keySet()
                : replayEngine.describeUnterminatedSessions();
            log.atWarn()
                .setMessage("Replay shutdown has not finished stage {}.  Unterminated connection sessions={}")
                .addArgument(stage::get)
                .addArgument(unterminatedSessions)
                .log();
            reportShutdownProgressUntilComplete(stage, completion, replayEngine);
        }, CompletableFuture.delayedExecutor(SHUTDOWN_PROGRESS_REPORT_INTERVAL.toSeconds(), TimeUnit.SECONDS));
    }

    @FunctionalInterface
    interface LifecycleCleanupStep {
        void run() throws ExecutionException, InterruptedException;
    }

    static void runLifecycleCleanup(
        Throwable primaryFailure,
        LifecycleCleanupStep... cleanupSteps
    ) throws ExecutionException, InterruptedException {
        var failure = primaryFailure;
        var interrupted = Thread.interrupted() || primaryFailure instanceof InterruptedException;
        for (var cleanupStep : cleanupSteps) {
            try {
                cleanupStep.run();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    interrupted = true;
                    Thread.interrupted();
                }
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (interrupted) {
            // Callers may translate or swallow the checked exception, so retain the cancellation signal too.
            Thread.currentThread().interrupt();
        }
        rethrowLifecycleFailure(failure);
    }

    private static void rethrowLifecycleFailure(
        Throwable failure
    ) throws ExecutionException, InterruptedException {
        if (failure == null) {
            return;
        }
        if (failure instanceof InterruptedException interruptedException) {
            throw interruptedException;
        }
        if (failure instanceof ExecutionException executionException) {
            throw executionException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new ExecutionException(failure);
    }

    private void awaitReplayShutdownForIntake() throws ExecutionException, InterruptedException {
        var shutdown = shutdownFutureRef.get();
        if (shutdown == null) {
            shutdown = shutdown(null);
        }
        shutdown.get();
    }

    void finishIntakeLifecycle(
        ReplayIntakeOwner replayIntakeOwner,
        CompletableFuture<Void> readingCompletion,
        Throwable primaryFailure,
        LifecycleCleanupStep finishReplay
    ) throws ExecutionException, InterruptedException {
        runLifecycleCleanup(
            primaryFailure,
            () -> {
                if (!readingCompletion.isDone()) {
                    replayIntakeOwner.stopReading().toCompletableFuture().get();
                    readingCompletion.get();
                }
            },
            () -> replayIntakeOwner.closeAccumulator().toCompletableFuture().get(),
            () -> replayIntakeOwner.fence().toCompletableFuture().get(),
            finishReplay,
            this::awaitReplayShutdownForIntake,
            () -> {
                assert fatalShutdownClaimed.get() || requestWorkTracker.isEmpty()
                    : "expected orderly shutdown to finalize every in-flight request";
            },
            () -> replayIntakeOwner.stopOwner().toCompletableFuture().get(),
            () -> {
                replayIntakeOwner.termination().toCompletableFuture().get();
                if (intakeOwner == replayIntakeOwner) {
                    intakeOwner = null;
                }
            }
        );
    }

    @Override
    public void close() throws Exception {
        shutdown(null).get();
    }
}
