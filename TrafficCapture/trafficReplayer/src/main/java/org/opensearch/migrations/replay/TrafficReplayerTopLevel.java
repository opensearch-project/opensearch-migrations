package org.opensearch.migrations.replay;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.http.retries.RetryCollectingVisitorFactory;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.RecordDispositionLedger;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeMailbox;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
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

    public static final AtomicInteger targetConnectionPoolUniqueCounter = new AtomicInteger();
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
    private final Object intakeLifecycleLock = new Object();
    private final AtomicReference<Error> shutdownReasonRef;
    private final AtomicReference<CompletableFuture<Void>> shutdownFutureRef;

    public TrafficReplayerTopLevel(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentRequests,
        IStreamableWorkTracker<Void> workTracker
    ) {
        this(context, serverUri, authTransformerFactory, jsonTransformerSupplier,
            clientConnectionPool, maxConcurrentRequests, workTracker, new BulkItemErrorClassifier());
    }

    public TrafficReplayerTopLevel(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentRequests,
        IStreamableWorkTracker<Void> workTracker,
        BulkItemErrorClassifier errorClassifier
    ) {
        this(
            context,
            serverUri,
            authTransformerFactory,
            jsonTransformerSupplier,
            clientConnectionPool,
            maxConcurrentRequests,
            workTracker,
            errorClassifier,
            ExceptionTypeAllowlist.empty()
        );
    }

    public TrafficReplayerTopLevel(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentRequests,
        IStreamableWorkTracker<Void> workTracker,
        BulkItemErrorClassifier errorClassifier,
        ExceptionTypeAllowlist poisonAllowlist
    ) {
        super(
            context,
            serverUri,
            authTransformerFactory,
            jsonTransformerSupplier,
            maxConcurrentRequests,
            workTracker,
            new RetryCollectingVisitorFactory(new OpenSearchDefaultRetry(errorClassifier)),
            new TargetResponseClassifier(errorClassifier, poisonAllowlist)
        );
        this.clientConnectionPool = clientConnectionPool;
        allRemainingWorkFutureOrShutdownSignalRef = new AtomicReference<>();
        shutdownReasonRef = new AtomicReference<>();
        shutdownFutureRef = new AtomicReference<>();
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
                : getTargetConnectionPoolName(targetConnectionPoolUniqueCounter.getAndIncrement()),
            numSendingThreads,
            metrics
        );
    }

    public static String getTargetConnectionPoolName(int i) {
        return TARGET_CONNECTION_POOL_NAME + (i == 0 ? "" : Integer.toString(i));
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
        var intakeMailbox = new ReplayIntakeMailbox();
        var permitPool = new AsyncPermitPool(
            maxConcurrentRequests,
            intakeMailbox,
            topLevelContext.getPermitPoolMetrics()
        );
        intakeMailboxRef.set(intakeMailbox);
        permitPoolRef.set(permitPool);
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, targetServerResponseTimeout),
            trafficSource::acknowledgeSessionTermination,
            topLevelContext.getConnectionActorMetrics(),
            topLevelContext.getTargetExchangeStateMetrics(),
            topLevelContext.getResourceOwnershipMetrics(),
            error -> shutdown(error)
        );
        var readGate = new ReplayReadGate(trafficSource.getBufferTimeWindow(), trafficSource);
        var progressController = new ReplayProgressController(intakeMailbox, readGate);
        var replayEngine = new ReplayEngine(
            senderOrchestrator,
            trafficSource,
            timeShifter,
            progressController
        );
        this.currentReplayEngine.set(replayEngine);
        var accumulationCallbacks = new TrafficReplayerAccumulationCallbacks(
            replayEngine,
            tupleWriter,
            resultTupleConsumer,
            tupleObserver,
            trafficSource,
            quiescentDuration,
            permitPool
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
                trafficSource::updateScanBlocker
            );
        this.currentAccumulator.set(trafficToHttpTransactionAccumulator);
        try {
            pullCaptureFromSourceToAccumulator(trafficSource, trafficToHttpTransactionAccumulator, intakeMailbox);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Terminating runReplay due to exception").log();
            throw e;
        } finally {
            try {
                intakeMailbox.runUntilIdle();
                trafficToHttpTransactionAccumulator.close();
                wrapUpWorkAndEmitSummary(replayEngine, trafficToHttpTransactionAccumulator);
                assert shutdownFutureRef.get() != null || requestWorkTracker.isEmpty()
                    : "expected to wait for all the in flight requests to fully flush and self destruct themselves";
            } finally {
                finishIntakeLifecycle(intakeMailbox);
            }
        }
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
        var logLevel = primaryLogLevel;
        for (var timeout = Duration.ofSeconds(60);; timeout = timeout.multipliedBy(2)) {
            if (shutdownFutureRef.get() != null) {
                log.warn("Not waiting for work because the TrafficReplayer is shutting down.");
                break;
            }
            try {
                waitForRemainingWork(logLevel, timeout);
                break;
            } catch (TimeoutException e) {
                log.atLevel(logLevel).log("Timed out while waiting for the remaining requests to be finalized...");
                logLevel = secondaryLogLevel;
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

        var intakeMailbox = intakeMailboxRef.get();
        if (intakeMailbox != null && intakeMailbox.isOwnerThread()) {
            intakeMailbox.runUntilIdle();
        }

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
        var ledger = dispositionLedgerRef.get();
        var recordDispositionFuture = ledger == null
            ? CompletableFuture.<Void>completedFuture(null)
            : ledger.whenQuiescent().toCompletableFuture();
        var allWorkFuture = new TextTrackedFuture<>(
            combineReplayDrainGates(
                requestWorkFuture.future,
                replayQuiescenceFuture,
                recordDispositionFuture
            ),
            () -> "TrafficReplayer.AllWorkFinished"
        );
        try {
            if (allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, allWorkFuture)) {
                if (intakeMailbox != null && intakeMailbox.isOwnerThread()) {
                    intakeMailbox.await(allWorkFuture.future, timeout);
                } else {
                    allWorkFuture.get(timeout);
                }
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
        CompletionStage<Void> replayQuiescence,
        CompletionStage<Void> recordDisposition
    ) {
        return CompletableFuture.allOf(
            requestWork.toCompletableFuture(),
            replayQuiescence.toCompletableFuture(),
            recordDisposition.toCompletableFuture()
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

    @Override
    protected boolean shouldRetry() {
        return !stopReadingRef.get();
    }

    @SneakyThrows
    @Override
    public @NonNull CompletableFuture<Void> shutdown(Error error) {
        log.atWarn().setCause(error).setMessage("Shutting down {}").addArgument(this).log();
        shutdownReasonRef.compareAndSet(null, error);
        var existingShutdown = claimShutdown(error);
        if (existingShutdown != null) {
            return existingShutdown;
        }
        stopReadingRef.set(true);
        Optional.ofNullable(this.nextChunkFutureRef.get()).ifPresent(f -> f.cancel(true));
        var cancellationCause = new CancellationException("replay is shutting down");
        var replayEngine = currentReplayEngine.get();
        var permitPool = permitPoolRef.get();
        if (permitPool != null) {
            permitPool.close(cancellationCause);
        }

        // Releasing Netty's event loops must not depend on the actors settling: one session that never
        // reaches termination would otherwise hold every thread in the pool and keep the process alive.
        // Reaching this bound is always a bug, and the shutdown watchdog above names the stuck session.
        //
        // Timing out here and shutting the pool down anyway is safe only because of the four event-loop
        // death gates documented in docs/replayerHardenedArchitectureDesign.md, section 16.3.  A session's
        // event loop is both its channel's I/O thread and its actor's mailbox, so once this pool goes away
        // nothing can advance a session that is still live: work parked on the network has no thread left
        // to complete it, and posted commands are dropped.  Those gates turn that into prompt cancellation
        // rather than a hang.  Cancellation always retains records (never commits), so the cost of landing
        // here is re-replaying in-flight work after restart, not data loss.  If you change this ordering or
        // the timeout, re-read 16.3 first -- removing any one of the four gates reintroduces a shutdown
        // that never completes.
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
        var dispositionLedger = dispositionLedgerRef.get();
        var completion = new CompletableFuture<Void>();
        var stage = new AtomicReference<>(ShutdownStage.SEALING_RECORD_REGISTRATIONS);
        Runnable beginShutdown = () -> {
            try {
                var registrationFence = dispositionLedger == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : dispositionLedger.sealRegistrations();
                registrationFence
                    .thenCompose(ignored -> {
                        stage.set(ShutdownStage.TERMINATING_CONNECTION_ACTORS);
                        return replayEngine == null
                            ? CompletableFuture.<Void>completedFuture(null)
                            : replayEngine.shutdownConnections(cause);
                    })
                    .thenCompose(ignored -> {
                        stage.set(ShutdownStage.SETTLING_RECORD_DISPOSITIONS);
                        return dispositionLedger == null
                            ? CompletableFuture.<Void>completedFuture(null)
                            : dispositionLedger.whenQuiescent();
                    })
                    .whenComplete((ignored, failure) -> {
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
        reportShutdownProgressUntilComplete(stage, completion, replayEngine, dispositionLedger);
        synchronized (intakeLifecycleLock) {
            var intakeMailbox = intakeMailboxRef.get();
            if (intakeMailbox != null) {
                intakeMailbox.execute(beginShutdown);
                return completion;
            }
        }
        beginShutdown.run();
        return completion;
    }

    private enum ShutdownStage {
        SEALING_RECORD_REGISTRATIONS,
        TERMINATING_CONNECTION_ACTORS,
        SETTLING_RECORD_DISPOSITIONS,
        DONE
    }

    /**
     * A shutdown that cannot finish is otherwise silent, which leaves nothing to diagnose from. Name
     * the stage that hasn't finished along with the work it is still waiting on.
     */
    private void reportShutdownProgressUntilComplete(
        AtomicReference<ShutdownStage> stage,
        CompletableFuture<Void> completion,
        ReplayEngine replayEngine,
        RecordDispositionLedger dispositionLedger
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
            if (dispositionLedger != null) {
                dispositionLedger.unresolvedObligations().whenComplete((obligations, failure) ->
                    log.atWarn()
                        .setCause(failure)
                        .setMessage("Record obligations that shutdown is still waiting on: {}")
                        .addArgument(obligations)
                        .log()
                );
            }
            reportShutdownProgressUntilComplete(stage, completion, replayEngine, dispositionLedger);
        }, CompletableFuture.delayedExecutor(SHUTDOWN_PROGRESS_REPORT_INTERVAL.toSeconds(), TimeUnit.SECONDS));
    }

    void finishIntakeLifecycle(
        ReplayIntakeMailbox intakeMailbox
    ) throws ExecutionException, InterruptedException {
        while (true) {
            CompletableFuture<Void> shutdown;
            synchronized (intakeLifecycleLock) {
                intakeMailbox.runUntilIdle();
                var dispositionLedger = dispositionLedgerRef.get();
                if (dispositionLedger != null) {
                    intakeMailbox.await(dispositionLedger.sealRegistrations());
                }
                shutdown = shutdownFutureRef.get();
                if (shutdown == null || shutdown.isDone()) {
                    intakeMailboxRef.compareAndSet(intakeMailbox, null);
                    return;
                }
            }
            intakeMailbox.await(shutdown);
        }
    }

    @Override
    public void close() throws Exception {
        shutdown(null).get();
    }
}
