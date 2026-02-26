package org.opensearch.migrations.replay;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.http.retries.RetryCollectingVisitorFactory;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
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

    public static final AtomicInteger targetConnectionPoolUniqueCounter = new AtomicInteger();

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

    public TrafficReplayerTopLevel(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        ClientConnectionPool clientConnectionPool,
        TrafficStreamLimiter trafficStreamLimiter,
        IStreamableWorkTracker<Void> workTracker
    ) {
        super(
            context,
            serverUri,
            authTransformerFactory,
            jsonTransformerSupplier,
            trafficStreamLimiter,
            workTracker,
            new RetryCollectingVisitorFactory(new OpenSearchDefaultRetry())
        );
        this.clientConnectionPool = clientConnectionPool;
        allRemainingWorkFutureOrShutdownSignalRef = new AtomicReference<>();
        shutdownReasonRef = new AtomicReference<>();
        shutdownFutureRef = new AtomicReference<>();
    }


    public static ClientConnectionPool
    makeNettyPacketConsumerConnectionPool(URI serverUri, boolean allowInsecureConnections, int numSendingThreads) {
        return makeNettyPacketConsumerConnectionPool(serverUri, allowInsecureConnections, numSendingThreads, null);
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(
        URI serverUri,
        boolean allowInsecureConnections,
        int numSendingThreads,
        String connectionPoolName
    ) {
        return new ClientConnectionPool(
            NettyPacketToHttpConsumer.createClientConnectionFactory(
                loadSslContext(serverUri, allowInsecureConnections), serverUri),
            connectionPoolName != null
                ? connectionPoolName
                : getTargetConnectionPoolName(targetConnectionPoolUniqueCounter.getAndIncrement()),
            numSendingThreads
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
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Duration quiescentDuration
    ) throws InterruptedException, ExecutionException {
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, targetServerResponseTimeout)
        );
        var replayEngine = new ReplayEngine(senderOrchestrator, trafficSource, timeShifter);
        // Wire session close callback so KafkaTrafficCaptureSource can track synthetic close drain
        clientConnectionPool.setGlobalOnSessionClose(session ->
            trafficSource.onNetworkConnectionClosed(
                session.getChannelKeyContext().getConnectionId(),
                // sessionNumber is the key used in the pool — derive from the session's context
                // For now use 0 as a placeholder; full GenerationalSessionKey wiring is Phase A4
                0,
                session.generation
            )
        );
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
            new CapturedTrafficToHttpTransactionAccumulator(
                observedPacketConnectionTimeout,
                "(see command line option " + TrafficReplayer.PACKET_TIMEOUT_SECONDS_PARAMETER_NAME + ")",
                new TrafficReplayerAccumulationCallbacks(replayEngine, resultTupleConsumer, trafficSource,
                    quiescentDuration)
            );
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
            assert shutdownFutureRef.get() != null || requestWorkTracker.isEmpty()
                : "expected to wait for all the in flight requests to fully flush and self destruct themselves";
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
        Consumer<SourceTargetCaptureTuple> resultTupleConsumer,
        Duration quiescentDuration
    ) throws TrafficReplayer.TerminationException, ExecutionException, InterruptedException {
        try {
            setupRunAndWaitForReplayToFinish(
                observedPacketConnectionTimeout,
                targetServerResponseTimeout,
                trafficSource,
                timeShifter,
                resultTupleConsumer,
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

        if (!liveTrafficStreamLimiter.isStopped()) {
            var streamLimiterHasRunEverything = new CompletableFuture<Void>();
            liveTrafficStreamLimiter.queueWork(1, null, wi -> {
                streamLimiterHasRunEverything.complete(null);
                liveTrafficStreamLimiter.doneProcessing(wi);
            });
            streamLimiterHasRunEverything.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        var allWorkFuture = TextTrackedFuture.allOf(
            allCompletableFuturesArray,
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
        if (!shutdownFutureRef.compareAndSet(null, new CompletableFuture<>())) {
            log.atError().setMessage("Shutdown was already signaled by {}.  Ignoring this shutdown request due to {}.")
                .addArgument(shutdownReasonRef::get)
                .addArgument(error)
                .log();
            return shutdownFutureRef.get();
        }
        stopReadingRef.set(true);
        liveTrafficStreamLimiter.close();


        var nettyShutdownFuture = clientConnectionPool.shutdownNow();
        nettyShutdownFuture.whenComplete((v, t) -> {
            if (t != null) {
                shutdownFutureRef.get().completeExceptionally(t);
            } else {
                shutdownFutureRef.get().complete(null);
            }
        });
        Optional.ofNullable(this.nextChunkFutureRef.get()).ifPresent(f -> f.cancel(true));
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
        var shutdownFuture = shutdownFutureRef.get();
        log.atWarn().setMessage("Shutdown setup has been initiated").log();
        return shutdownFuture;
    }

    @Override
    public void close() throws Exception {
        shutdown(null).get();
    }
}
