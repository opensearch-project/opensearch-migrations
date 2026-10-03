package org.opensearch.migrations.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.RequestLifecycleInput;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;
import org.opensearch.migrations.replay.kafkasource.GraceIntervalWait;
import org.opensearch.migrations.replay.kafkasource.KafkaConsumerSourcePort;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationCancelled;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.TargetChannelPort;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.sink.TupleSink;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.sink.TupleWriter.PhysicalTupleSink;
import org.opensearch.migrations.replay.sink.TupleWriter.TransformedTuple;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleDropped;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleTransformer;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ProtocolViolationMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.util.ActiveContextMonitor;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;

/**
 * G5's production composition root. G9 owns starting and supervising its two owner loops.
 *
 * <p>The Kafka adapter, both owner queues, replay intake, process-local connection registry,
 * connection/request owners, tuple writer and context lifecycle are constructed as one reachable chain.
 * Every connection is published only after its first event-loop submission has been accepted.</p>
 */
@Slf4j
public final class TrafficReplayerTopLevel<P extends AutoCloseable, R, T>
    implements AutoCloseable {

    public static final int DEFAULT_READY_REQUESTS_BUFFER_PER_THREAD = 2;

    @FunctionalInterface
    public interface OwnerTaskRunner {
        void runAndWait(EventLoop eventLoop, Runnable task);
    }

    @FunctionalInterface
    public interface TargetChannelFactory<P, R> {
        TargetChannelPort<P, R> create(
            ConnectionProcessingId connectionProcessingId,
            EventLoop eventLoop,
            IReplayContexts.IConnectionContext connectionContext
        );
    }

    @FunctionalInterface
    public interface ManagedTupleTransformerFactory<T> {
        ManagedTupleTransformer<T> create(int workerIndex);
    }

    public interface ManagedTupleTransformer<T> extends TupleTransformer<T>, AutoCloseable {
        @Override
        void close() throws Exception;
    }

    @FunctionalInterface
    public interface ManagedPhysicalTupleSinkFactory<T> {
        ManagedPhysicalTupleSink<T> create(int workerIndex);
    }

    public interface ManagedPhysicalTupleSink<T> extends PhysicalTupleSink<T>, AutoCloseable {
        @Override
        void flush();

        @Override
        void close() throws Exception;
    }

    public record Configuration<P extends AutoCloseable, R, T>(
        @NonNull Clock clock,
        @NonNull LongSupplier nanoTime,
        @NonNull Function<Instant, Instant> replayTimeMapper,
        @NonNull Function<ConnectionProcessingId, EventLoop> eventLoopFor,
        @NonNull List<EventLoop> targetEventLoops,
        @NonNull OwnerTaskRunner ownerTaskRunner,
        @NonNull BiFunction<
            ConnectionProcessingId,
            IReplayContexts.IConnectionContext,
            RequestReplayOwner.RequestPreparer<HttpMessageAndTimestamp.Request, P>
        > requestPreparerFactory,
        @NonNull RequestReplayOwner.RetryPolicy<P, R, HttpMessageAndTimestamp.Response> retryPolicy,
        @NonNull TargetChannelFactory<P, R> targetChannelFactory,
        @NonNull RequestReplayOwner.TupleFactory<
            HttpMessageAndTimestamp.Request,
            P,
            R,
            HttpMessageAndTimestamp.Response,
            T
        > tupleFactory,
        @NonNull RequestReplayOwner.ResourceReleaser<
            HttpMessageAndTimestamp.Request,
            P,
            R,
            HttpMessageAndTimestamp.Response
        > resourceReleaser,
        @NonNull ManagedTupleTransformerFactory<T> tupleTransformerFactory,
        @NonNull ManagedPhysicalTupleSinkFactory<T> tupleSinkFactory,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull TupleWriter.RetryDelayPolicy tupleRetryDelayPolicy,
        @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        int readyRequestsBufferPerThread,
        int maximumResponseRetries,
        int maximumTargetAttempts
    ) {
        public Configuration(
            @NonNull Clock clock,
            @NonNull LongSupplier nanoTime,
            @NonNull Function<Instant, Instant> replayTimeMapper,
            @NonNull Function<ConnectionProcessingId, EventLoop> eventLoopFor,
            @NonNull List<EventLoop> targetEventLoops,
            @NonNull OwnerTaskRunner ownerTaskRunner,
            @NonNull BiFunction<
                ConnectionProcessingId,
                IReplayContexts.IConnectionContext,
                RequestReplayOwner.RequestPreparer<HttpMessageAndTimestamp.Request, P>
            > requestPreparerFactory,
            @NonNull RequestReplayOwner.RetryPolicy<P, R, HttpMessageAndTimestamp.Response> retryPolicy,
            @NonNull TargetChannelFactory<P, R> targetChannelFactory,
            @NonNull RequestReplayOwner.TupleFactory<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response,
                T
            > tupleFactory,
            @NonNull RequestReplayOwner.ResourceReleaser<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response
            > resourceReleaser,
            @NonNull ManagedTupleTransformerFactory<T> tupleTransformerFactory,
            @NonNull ManagedPhysicalTupleSinkFactory<T> tupleSinkFactory,
            @NonNull Consumer<T> tupleReleaser,
            @NonNull Duration tupleRetryDelay,
            @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
            int readyRequestsBufferPerThread,
            int maximumResponseRetries,
            int maximumTargetAttempts
        ) {
            this(
                clock,
                nanoTime,
                replayTimeMapper,
                eventLoopFor,
                targetEventLoops,
                ownerTaskRunner,
                requestPreparerFactory,
                retryPolicy,
                targetChannelFactory,
                tupleFactory,
                resourceReleaser,
                tupleTransformerFactory,
                tupleSinkFactory,
                tupleReleaser,
                TupleWriter.fixedRetryDelay(tupleRetryDelay),
                brokerTimeConfiguration,
                readyRequestsBufferPerThread,
                maximumResponseRetries,
                maximumTargetAttempts
            );
        }

        public Configuration(
            @NonNull Clock clock,
            @NonNull LongSupplier nanoTime,
            @NonNull Function<Instant, Instant> replayTimeMapper,
            @NonNull Function<ConnectionProcessingId, EventLoop> eventLoopFor,
            @NonNull List<EventLoop> targetEventLoops,
            @NonNull OwnerTaskRunner ownerTaskRunner,
            @NonNull BiFunction<
                ConnectionProcessingId,
                IReplayContexts.IConnectionContext,
                RequestReplayOwner.RequestPreparer<HttpMessageAndTimestamp.Request, P>
            > requestPreparerFactory,
            @NonNull RequestReplayOwner.RetryPolicy<P, R, HttpMessageAndTimestamp.Response> retryPolicy,
            @NonNull TargetChannelFactory<P, R> targetChannelFactory,
            @NonNull RequestReplayOwner.TupleFactory<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response,
                T
            > tupleFactory,
            @NonNull RequestReplayOwner.ResourceReleaser<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response
            > resourceReleaser,
            @NonNull ManagedTupleTransformerFactory<T> tupleTransformerFactory,
            @NonNull ManagedPhysicalTupleSinkFactory<T> tupleSinkFactory,
            @NonNull Consumer<T> tupleReleaser,
            @NonNull Duration tupleRetryDelay,
            @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
            int maximumResponseRetries,
            int maximumTargetAttempts
        ) {
            this(
                clock,
                nanoTime,
                replayTimeMapper,
                eventLoopFor,
                targetEventLoops,
                ownerTaskRunner,
                requestPreparerFactory,
                retryPolicy,
                targetChannelFactory,
                tupleFactory,
                resourceReleaser,
                tupleTransformerFactory,
                tupleSinkFactory,
                tupleReleaser,
                TupleWriter.fixedRetryDelay(tupleRetryDelay),
                brokerTimeConfiguration,
                DEFAULT_READY_REQUESTS_BUFFER_PER_THREAD,
                maximumResponseRetries,
                maximumTargetAttempts
            );
        }

        public Configuration {
            if (maximumTargetAttempts <= 0) {
                throw new IllegalArgumentException("maximumTargetAttempts must be positive");
            }
            if (maximumResponseRetries <= 0) {
                throw new IllegalArgumentException("maximumResponseRetries must be positive");
            }
            if (readyRequestsBufferPerThread <= 0) {
                throw new IllegalArgumentException(
                    "readyRequestsBufferPerThread must be positive"
                );
            }
            targetEventLoops = List.copyOf(targetEventLoops);
            if (targetEventLoops.isEmpty()) {
                throw new IllegalArgumentException("targetEventLoops must not be empty");
            }
            var distinct = java.util.Collections.newSetFromMap(
                new IdentityHashMap<EventLoop, Boolean>()
            );
            distinct.addAll(targetEventLoops);
            if (distinct.size() != targetEventLoops.size()) {
                throw new IllegalArgumentException("targetEventLoops must contain distinct owners");
            }
            Math.multiplyExact(
                readyRequestsBufferPerThread,
                targetEventLoops.size()
            );
        }

        public int retryReadyRequestSupplyTarget() {
            return Math.multiplyExact(
                readyRequestsBufferPerThread,
                targetEventLoops.size()
            );
        }
    }

    public static OwnerTaskRunner deployedOwnerTaskRunner() {
        return (eventLoop, task) -> eventLoop.submit(task).syncUninterruptibly();
    }

    private final KafkaSourceInputQueue sourceInputs;
    private final ReplayIntakeInputQueue intakeInputs;
    private final KafkaSourceOwner sourceOwner;
    private final ReplayIntakeOwner intakeOwner;
    private final ConnectionAssemblySink connectionAssemblySink;
    private final Configuration<P, R, T> configuration;
    private final ChannelContextManager contextManager;
    private final TargetAttemptPermitProvider permitProvider;
    private final ProcessSupervisor.FailureSink fatalSink;
    private final ProtocolViolationTerminator protocolViolationTerminator;
    private final ActiveContextMonitor activityMonitor;
    private final Runnable kafkaWakeup;
    private final List<TupleWriterWorker> tupleWriterWorkers;
    private final Map<EventLoop, TupleWriterWorker> tupleWriterWorkersByEventLoop;
    private final AtomicBoolean targetEventLoopTerminationExpected = new AtomicBoolean();
    private final AtomicBoolean fatalTerminationStarted = new AtomicBoolean();
    private final CompletableFuture<ProcessSupervisor.FatalSignal> firstFatalSignal =
        new CompletableFuture<>();
    private boolean intakeStarted;

    public TrafficReplayerTopLevel(
        @NonNull org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        @NonNull RootReplayerContext rootContext,
        @NonNull Configuration<P, R, T> configuration,
        @NonNull Duration kafkaPollTimeout,
        @NonNull ProcessSupervisor.FailureSink fatalSink
    ) {
        this(
            consumer,
            rootContext,
            configuration,
            kafkaPollTimeout,
            KafkaSourceOwner.DEFAULT_REVOCATION_GRACE,
            System::exit,
            fatalSink
        );
    }

    public TrafficReplayerTopLevel(
        @NonNull org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        @NonNull RootReplayerContext rootContext,
        @NonNull Configuration<P, R, T> configuration,
        @NonNull Duration kafkaPollTimeout,
        @NonNull Duration cancellationGrace,
        @NonNull ProcessSupervisor.FailureSink fatalSink
    ) {
        this(
            consumer,
            rootContext,
            configuration,
            kafkaPollTimeout,
            cancellationGrace,
            System::exit,
            fatalSink
        );
    }

    public TrafficReplayerTopLevel(
        @NonNull org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        @NonNull RootReplayerContext rootContext,
        @NonNull Configuration<P, R, T> configuration,
        @NonNull Duration kafkaPollTimeout,
        @NonNull Duration cancellationGrace,
        @NonNull IntConsumer protocolViolationTermination,
        @NonNull ProcessSupervisor.FailureSink fatalSink
    ) {
        this.configuration = configuration;
        this.fatalSink = fatalSink;
        this.kafkaWakeup = consumer::wakeup;
        this.protocolViolationTerminator = ProtocolViolationTerminator.system(
            protocolViolationTermination,
            new ProtocolViolationMetrics(
                rootContext.getMeterProvider().get(RootReplayerContext.SCOPE_NAME)
            )
        );
        this.contextManager = new ChannelContextManager(rootContext);
        var wakeupController = new WakeupController(consumer::wakeup, rootContext);
        this.sourceInputs = new KafkaSourceInputQueue(wakeupController);
        this.intakeInputs = new ReplayIntakeInputQueue();
        this.permitProvider = new TargetAttemptPermitProvider(
            configuration.maximumTargetAttempts(),
            new AtomicInteger(),
            rootContext.getTargetAttemptPermitMetrics(),
            unexpectedFailure("target-attempt permit provider", "owner transition")::accept,
            configuration.nanoTime()
        );
        this.tupleWriterWorkers = createTupleWriterWorkers();
        this.tupleWriterWorkersByEventLoop = new IdentityHashMap<>();
        tupleWriterWorkers.forEach(worker ->
            tupleWriterWorkersByEventLoop.put(worker.eventLoop, worker)
        );
        watchTargetEventLoops();
        this.connectionAssemblySink = new ConnectionAssemblySink(
            configuration.replayTimeMapper()
        );
        this.activityMonitor = ActiveContextMonitor.system(
            configuration.clock(),
            this::activitySnapshots
        );
        this.intakeOwner = new ReplayIntakeOwner(
            intakeInputs,
            sourceInputs,
            connectionAssemblySink,
            unexpectedFailure("replay intake owner", "owner loop")::accept,
            rootContext.getReplayIntakeMetrics(),
            ReplayIntakeOwner.RecordObserver.NOOP,
            configuration.brokerTimeConfiguration(),
            configuration.retryReadyRequestSupplyTarget()
        );
        this.sourceOwner = new KafkaSourceOwner(
            new KafkaConsumerSourcePort(consumer, kafkaPollTimeout),
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            configuration.nanoTime(),
            GraceIntervalWait.blockingOn(sourceInputs, configuration.nanoTime()),
            rootContext.getKafkaCommitStateMetrics(),
            rootContext::createKafkaRecordContext,
            violation -> protocolViolationTerminator.begin(
                violation.recordId(),
                violation.diagnostic()
            )
        );
    }

    private List<TupleWriterWorker> createTupleWriterWorkers() {
        var created = new ArrayList<TupleWriterWorker>();
        try {
            for (var workerIndex = 0; workerIndex < configuration.targetEventLoops().size(); workerIndex++) {
                var eventLoop = configuration.targetEventLoops().get(workerIndex);
                created.add(createTupleWriterWorker(workerIndex, eventLoop));
            }
            return List.copyOf(created);
        } catch (Throwable failure) {
            for (var worker : created) {
                worker.closeAfterConstructionFailure(failure);
            }
            throw failure;
        }
    }

    private TupleWriterWorker createTupleWriterWorker(
        int workerIndex,
        EventLoop eventLoop
    ) {
        var transformer = Objects.requireNonNull(
            configuration.tupleTransformerFactory().create(workerIndex),
            "tuple transformer factory returned null for worker " + workerIndex
        );
        ManagedPhysicalTupleSink<T> sink;
        try {
            sink = Objects.requireNonNull(
                configuration.tupleSinkFactory().create(workerIndex),
                "tuple sink factory returned null for worker " + workerIndex
            );
        } catch (Throwable failure) {
            closePartiallyConstructed(transformer, "tuple transformer", workerIndex, failure);
            throw failure;
        }
        return new TupleWriterWorker(workerIndex, eventLoop, transformer, sink);
    }

    private void closePartiallyConstructed(
        AutoCloseable closeable,
        String component,
        int workerIndex,
        Throwable constructionFailure
    ) {
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            constructionFailure.addSuppressed(new IllegalStateException(
                "Failed to close " + component + " for tuple worker " + workerIndex,
                closeFailure
            ));
        }
    }

    private void watchTargetEventLoops() {
        tupleWriterWorkers.forEach(worker ->
            worker.eventLoop.terminationFuture().addListener(ignored -> {
                if (targetEventLoopTerminationExpected.get()) {
                    return;
                }
                var cause = worker.eventLoop.terminationFuture().cause();
                signalFatal(new ProcessSupervisor.FatalSignal(
                    ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED,
                    "target event loop worker " + worker.workerIndex,
                    "termination future",
                    new Error(
                        "Target event loop worker "
                            + worker.workerIndex
                            + " terminated before orderly process shutdown",
                        cause
                    )
                ));
            })
        );
    }

    private Consumer<Error> unexpectedFailure(String owner, String operation) {
        return failure -> signalFatal(new ProcessSupervisor.FatalSignal(
            ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR,
            owner,
            operation,
            failure
        ));
    }

    private Consumer<Error> eventLoopOwnedFailure(
        EventLoop eventLoop,
        String owner,
        String operation
    ) {
        return failure -> signalFatal(new ProcessSupervisor.FatalSignal(
            classifyEventLoopOwnedFailure(eventLoop, failure),
            owner,
            operation,
            failure
        ));
    }

    static ProcessSupervisor.Reason classifyEventLoopOwnedFailure(
        EventLoop eventLoop,
        Throwable failure
    ) {
        var eventLoopUnavailable =
            eventLoop.isShuttingDown() || eventLoop.isShutdown() || eventLoop.isTerminated();
        for (var current = failure; current != null; current = current.getCause()) {
            if (eventLoopUnavailable && current instanceof RejectedExecutionException) {
                return ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED;
            }
        }
        return ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR;
    }

    private void signalFatal(ProcessSupervisor.FatalSignal signal) {
        fatalTerminationStarted.set(true);
        firstFatalSignal.complete(signal);
        fatalSink.onFatal(signal);
    }

    private void reportUnexpectedFailure(
        String owner,
        String operation,
        Throwable failure
    ) {
        var error = failure instanceof Error existing
            ? existing
            : new Error(owner + " failed during " + operation, failure);
        unexpectedFailure(owner, operation).accept(error);
    }

    /**
     * Abrupt failure-path input stop. It rejects new cross-owner submissions and wakes a blocked Kafka poll;
     * it does not enter G8's orderly fence and never waits for an owner or event-loop group.
     */
    public void stopNewInputForFatal(ProcessSupervisor.FatalSignal ignored) {
        fatalTerminationStarted.set(true);
        firstFatalSignal.complete(ignored);
        sourceInputs.close();
        intakeInputs.closeNow();
        kafkaWakeup.run();
    }

    /**
     * Called only after G8's orderly drain has completed, immediately before the process closes target loops.
     */
    public void allowTargetEventLoopTermination() {
        targetEventLoopTerminationExpected.set(true);
    }

    public void startIntake() {
        if (intakeStarted) {
            throw new IllegalStateException("replay intake already started");
        }
        intakeStarted = true;
        activityMonitor.start();
        try {
            intakeOwner.start();
        } catch (Throwable failure) {
            activityMonitor.close();
            throw failure;
        }
    }

    public void runSourceOnce() {
        try {
            sourceOwner.runOnce();
        } catch (Throwable failure) {
            reportUnexpectedFailure(
                "Kafka source owner",
                "run-loop iteration",
                failure
            );
        }
    }

    public void wakeSourceOwner() {
        kafkaWakeup.run();
    }

    public KafkaSourceOwner sourceOwner() {
        return sourceOwner;
    }

    public ReplayIntakeOwner intakeOwner() {
        return intakeOwner;
    }

    public ReplayIntakeInputQueue intakeInputs() {
        return intakeInputs;
    }

    public KafkaSourceInputQueue sourceInputs() {
        return sourceInputs;
    }

    Configuration<P, R, T> configuration() {
        return configuration;
    }

    int tupleWriterWorkerCount() {
        return tupleWriterWorkers.size();
    }

    int activeConnectionCount() {
        return connectionAssemblySink.connections.size();
    }

    java.util.List<org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot>
    activitySnapshot(ConnectionProcessingId connectionProcessingId) {
        return connectionAssemblySink.requireConnection(connectionProcessingId)
            .owner.activitySnapshot();
    }

    java.util.List<org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot>
    activitySnapshots() {
        var unique =
            new java.util.LinkedHashSet<
                org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot
            >();
        connectionAssemblySink.connections.values().forEach(binding ->
            unique.addAll(binding.owner.activitySnapshot())
        );
        tupleWriterWorkers.forEach(worker ->
            unique.addAll(worker.writer.operations().snapshots())
        );
        return List.copyOf(unique);
    }

    /**
     * The caller installs this listener on the same real consumer used to construct the source port.
     */
    public ConsumerRebalanceListener rebalanceListener() {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions
            ) {
                try {
                    sourceOwner.onPartitionsRevoked(partitions);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    reportUnexpectedFailure(
                        "Kafka source owner",
                        "partition revocation callback",
                        interrupted
                    );
                } catch (Throwable failure) {
                    reportUnexpectedFailure(
                        "Kafka source owner",
                        "partition revocation callback",
                        failure
                    );
                }
            }

            @Override
            public void onPartitionsAssigned(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions
            ) {
                try {
                    sourceOwner.onPartitionsAssigned(partitions);
                } catch (Throwable failure) {
                    reportUnexpectedFailure(
                        "Kafka source owner",
                        "partition assignment callback",
                        failure
                    );
                }
            }

            @Override
            public void onPartitionsLost(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions
            ) {
                try {
                    sourceOwner.onPartitionsLost(partitions);
                } catch (Throwable failure) {
                    reportUnexpectedFailure(
                        "Kafka source owner",
                        "partition loss callback",
                        failure
                    );
                }
            }
        };
    }

    public static ManagedTupleTransformer<Map<String, Object>> deployedTupleTransformer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier
    ) {
        return deployedTupleTransformer(transformerSupplier, null);
    }

    public static ManagedTupleTransformer<Map<String, Object>> deployedTupleTransformer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier,
        Supplier<IJsonTransformer> responsePostProcessorSupplier
    ) {
        var transformer = Objects.requireNonNull(
            transformerSupplier.get(),
            "tuple transformer supplier returned null"
        );
        final IJsonTransformer responsePostProcessor;
        try {
            responsePostProcessor = responsePostProcessorSupplier == null
                ? null
                : Objects.requireNonNull(
                    responsePostProcessorSupplier.get(),
                    "response post-processor supplier returned null"
                );
        } catch (Throwable failure) {
            try {
                transformer.close();
            } catch (Throwable closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        return new ManagedTupleTransformer<>() {
            @Override
            public TupleWriter.TupleTransformation<Map<String, Object>> transform(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                var postProcessedTuple = responsePostProcessor == null
                    ? tuple
                    : postProcessTargetResponses(responsePostProcessor, tuple);
                final Object transformed;
                try {
                    transformed = transformer.transformJson(postProcessedTuple);
                } catch (RequestFilteredException intentionalDrop) {
                    return new TupleDropped<>();
                }
                if (!(transformed instanceof Map<?, ?> transformedMap)) {
                    throw new IllegalArgumentException(
                        "Tuple transformer must return a JSON object or throw RequestFilteredException"
                    );
                }
                @SuppressWarnings("unchecked")
                var typed = (Map<String, Object>) transformedMap;
                return new TransformedTuple<>(typed);
            }

            @Override
            public void close() throws Exception {
                Throwable failure = null;
                try {
                    transformer.close();
                } catch (Throwable transformerFailure) {
                    failure = transformerFailure;
                }
                if (responsePostProcessor != null) {
                    try {
                        responsePostProcessor.close();
                    } catch (Throwable postProcessorFailure) {
                        if (failure == null) {
                            failure = postProcessorFailure;
                        } else if (failure != postProcessorFailure) {
                            failure.addSuppressed(postProcessorFailure);
                        }
                    }
                }
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> postProcessTargetResponses(
        @NonNull IJsonTransformer responsePostProcessor,
        @NonNull Map<String, Object> tuple
    ) {
        var targetResponsesValue = tuple.get("targetResponses");
        if (!(targetResponsesValue instanceof List<?> targetResponses)) {
            return tuple;
        }
        var processedResponses = new ArrayList<Map<String, Object>>(targetResponses.size());
        for (var index = 0; index < targetResponses.size(); index++) {
            var original = targetResponses.get(index);
            if (original == null) {
                processedResponses.add(null);
                continue;
            }
            try {
                var transformed = responsePostProcessor.transformJson(original);
                if (transformed != null && !(transformed instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(
                        "Response post-processor must return a JSON object or null"
                    );
                }
                processedResponses.add((Map<String, Object>) transformed);
            } catch (Exception failure) {
                log.atWarn()
                    .setCause(failure)
                    .setMessage("Response post-processor failed for response {}, leaving empty")
                    .addArgument(index)
                    .log();
                processedResponses.add(null);
            }
        }
        var processedTuple = new java.util.LinkedHashMap<>(tuple);
        processedTuple.put(
            "targetResponses",
            java.util.Collections.unmodifiableList(processedResponses)
        );
        return processedTuple;
    }

    public static RequestReplayOwner.RequestPreparer<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest
    > deployedRequestPreparer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier,
        IAuthTransformerFactory authTransformerFactory,
        @NonNull Duration packetInterval
    ) {
        if (packetInterval.isNegative()) {
            throw new IllegalArgumentException("packetInterval must not be negative");
        }
        return deployedRequestPreparer(
            transformerSupplier,
            authTransformerFactory,
            (ignoredSourceRequest, ignoredPreparedRequest) -> packetInterval
        );
    }

    public static RequestReplayOwner.RequestPreparer<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest
    > deployedRequestPreparer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier,
        IAuthTransformerFactory authTransformerFactory,
        double speedupFactor
    ) {
        if (!(speedupFactor > 0.0) || !Double.isFinite(speedupFactor)) {
            throw new IllegalArgumentException("speedupFactor must be finite and positive");
        }
        return deployedRequestPreparer(
            transformerSupplier,
            authTransformerFactory,
            (sourceRequest, preparedRequest) ->
                deployedPacketInterval(
                    sourceRequest,
                    preparedRequest,
                    speedupFactor
                )
        );
    }

    static Duration deployedPacketInterval(
        HttpMessageAndTimestamp.Request sourceRequest,
        org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest preparedRequest,
        double speedupFactor
    ) {
        if (!(speedupFactor > 0.0) || !Double.isFinite(speedupFactor)) {
            throw new IllegalArgumentException("speedupFactor must be finite and positive");
        }
        var packetCount = preparedRequest.numByteBufs();
        if (packetCount <= 1) {
            return Duration.ZERO;
        }
        var first = sourceRequest.getFirstPacketTimestamp();
        var last = sourceRequest.getLastPacketTimestamp();
        if (last == null || !last.isAfter(first)) {
            return Duration.ZERO;
        }
        var shiftedDurationMillis =
            (long) (Duration.between(first, last).toMillis() / speedupFactor);
        return Duration.ofMillis(shiftedDurationMillis)
            .dividedBy(packetCount - 1L);
    }

    static RequestReplayOwner.RequestPreparer<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest
    > deployedRequestPreparer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier,
        IAuthTransformerFactory authTransformerFactory,
        @NonNull BiFunction<
            HttpMessageAndTimestamp.Request,
            org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest,
            Duration
        > packetIntervalFactory
    ) {
        return (requestId, sourceRequest, replayContext) -> {
            var completion = new CompletableFuture<RequestPreparationResult<
                NettyPacketToHttpConsumer.PreparedRequest
            >>();
            var settled = new AtomicBoolean();
            final IJsonTransformer transformer;
            try {
                transformer = Objects.requireNonNull(
                    transformerSupplier.get(),
                    "request transformer supplier returned null"
                );
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
                return preparationOperation(completion, settled);
            }
            var consumer = new HttpJsonTransformingConsumer<ByteBufListProducer>(
                transformer,
                authTransformerFactory,
                new TransformedPacketReceiver(),
                replayContext
            );
            try {
                sourceRequest.stream().forEach(packet -> {
                    var input = Unpooled.wrappedBuffer(packet);
                    consumer.consumeBytes(input).future.join();
                });
                consumer.finalizeRequest().future.whenComplete((transformed, failure) -> {
                    Throwable terminalFailure = TrackedFuture.unwindPossibleCompletionException(failure);
                    try {
                        transformer.close();
                    } catch (Throwable closeFailure) {
                        if (terminalFailure == null) {
                            terminalFailure = closeFailure;
                        } else if (terminalFailure != closeFailure) {
                            terminalFailure.addSuppressed(closeFailure);
                        }
                    }
                    if (terminalFailure != null) {
                        if (containsRequestFilterRejection(terminalFailure)) {
                            completion.complete(new RequestPreparationReady<>(
                                null,
                                HttpRequestTransformationStatus.skipped()
                            ));
                        } else {
                            completion.completeExceptionally(terminalFailure);
                        }
                        return;
                    }
                    if (transformed == null) {
                        completion.completeExceptionally(new NullPointerException(
                            "request transformation completed without a result"
                        ));
                        return;
                    }
                    NettyPacketToHttpConsumer.PreparedRequest prepared = null;
                    try {
                        prepared = transformed.transformedOutput == null
                            ? null
                            : new NettyPacketToHttpConsumer.PreparedRequest(
                                transformed.transformedOutput,
                                Objects.requireNonNull(
                                    packetIntervalFactory.apply(
                                        sourceRequest,
                                        transformed.transformedOutput
                                    ),
                                    "packet interval factory returned null"
                                )
                            );
                        var result = new RequestPreparationReady<>(
                            prepared,
                            transformed.transformationStatus
                        );
                        if (!completion.complete(result) && prepared != null) {
                            prepared.close();
                        }
                    } catch (Throwable preparationFailure) {
                        if (prepared != null) {
                            try {
                                prepared.close();
                            } catch (Throwable closeFailure) {
                                if (preparationFailure != closeFailure) {
                                    preparationFailure.addSuppressed(closeFailure);
                                }
                            }
                        } else if (transformed.transformedOutput != null) {
                            try {
                                transformed.transformedOutput.close();
                            } catch (Throwable closeFailure) {
                                if (preparationFailure != closeFailure) {
                                    preparationFailure.addSuppressed(closeFailure);
                                }
                            }
                        }
                        completion.completeExceptionally(preparationFailure);
                    }
                });
            } catch (Throwable failure) {
                var terminalFailure =
                    TrackedFuture.unwindPossibleCompletionException(failure);
                try {
                    transformer.close();
                } catch (Throwable closeFailure) {
                    if (terminalFailure != closeFailure) {
                        terminalFailure.addSuppressed(closeFailure);
                    }
                }
                if (containsRequestFilterRejection(terminalFailure)) {
                    completion.complete(new RequestPreparationReady<>(
                        null,
                        HttpRequestTransformationStatus.skipped()
                    ));
                } else {
                    completion.completeExceptionally(terminalFailure);
                }
            }
            return preparationOperation(completion, settled);
        };
    }

    private static boolean containsRequestFilterRejection(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof RequestFilteredException) {
                return true;
            }
        }
        return false;
    }

    private static RequestReplayOwner.PreparationOperation<
        NettyPacketToHttpConsumer.PreparedRequest
    > preparationOperation(
        CompletableFuture<RequestPreparationResult<
            NettyPacketToHttpConsumer.PreparedRequest
        >> completion,
        AtomicBoolean settled
    ) {
        completion.whenComplete((ignored, failure) -> settled.set(true));
        return new RequestReplayOwner.PreparationOperation<>() {
            @Override
            public CompletionStage<RequestPreparationResult<
                NettyPacketToHttpConsumer.PreparedRequest
            >> completion() {
                return completion.minimalCompletionStage();
            }

            @Override
            public void cancel(CancellationException cause) {
                if (settled.compareAndSet(false, true)) {
                    completion.complete(new RequestPreparationCancelled<>(cause));
                }
            }
        };
    }

    public static RequestReplayOwner.TupleFactory<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response,
        Map<String, Object>
    > deployedTupleFactory() {
        return (replayContext, result) ->
            new ParsedHttpMessagesAsDicts(replayContext, result).toTupleMap(result);
    }

    public static ManagedPhysicalTupleSink<Map<String, Object>> deployedTupleSink(
        @NonNull TupleSink sink
    ) {
        return new ManagedPhysicalTupleSink<>() {
            @Override
            public CompletionStage<Void> write(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                var completion = new java.util.concurrent.CompletableFuture<Void>();
                sink.accept(tuple, completion);
                return completion.minimalCompletionStage();
            }

            @Override
            public void flush() {
                sink.flush();
            }

            @Override
            public void close() {
                sink.close();
            }
        };
    }

    @Override
    public void close() {
        if (!intakeStarted) {
            sourceOwner.beginOrderlyShutdown();
            sourceOwner.closeAfterOrderlyShutdown();
            sourceInputs.close();
            intakeInputs.closeNow();
            closeTupleWriterWorkers();
            closeProtocolViolationTerminator();
            activityMonitor.close();
            return;
        }
        sourceOwner.beginOrderlyShutdown();
        while (!sourceOwner.isOrderlyShutdownReadyToClose()) {
            if (fatalTerminationStarted.get()) {
                return;
            }
            sourceOwner.runOnce();
        }
        if (fatalTerminationStarted.get()) {
            return;
        }
        if (intakeInputs.requestStopAfterDraining()) {
            if (!awaitIntakeTerminationOrFatal()) {
                return;
            }
        }
        if (fatalTerminationStarted.get()) {
            return;
        }
        sourceOwner.closeAfterOrderlyShutdown();
        sourceInputs.close();
        closeTupleWriterWorkers();
        if (fatalTerminationStarted.get()) {
            return;
        }
        closeProtocolViolationTerminator();
        activityMonitor.close();
    }

    boolean awaitIntakeTerminationOrFatal() {
        return awaitOwnerTerminationUnlessFatal(
            intakeOwner.termination(),
            firstFatalSignal,
            fatalTerminationStarted::get
        );
    }

    static boolean awaitOwnerTerminationUnlessFatal(
        CompletionStage<Void> ownerTermination,
        CompletionStage<?> fatalSignal,
        java.util.function.BooleanSupplier fatalTerminationStarted
    ) {
        var termination = ownerTermination.toCompletableFuture();
        CompletableFuture.anyOf(termination, fatalSignal.toCompletableFuture()).join();
        if (fatalTerminationStarted.getAsBoolean()) {
            return false;
        }
        termination.join();
        return true;
    }

    private void closeProtocolViolationTerminator() {
        try {
            protocolViolationTerminator.close();
        } catch (Exception failure) {
            reportUnexpectedFailure(
                "protocol-violation terminator",
                "close",
                failure
            );
        }
    }

    private ConnectionBinding createConnection(
        ConnectionAssemblySink assemblySink,
        ConnectionProcessingId connectionId
    ) {
        var eventLoop = Objects.requireNonNull(
            configuration.eventLoopFor().apply(connectionId),
            "event-loop selector returned null"
        );
        var tupleWriterWorker = Objects.requireNonNull(
            tupleWriterWorkersByEventLoop.get(eventLoop),
            "event-loop selector returned an owner outside the configured worker set"
        );
        var connectionContext = contextManager.apply(connectionId);
        var terminalBinding = new AtomicReference<ConnectionBinding>();
        var owner = new TargetConnectionOwner<
            HttpMessageAndTimestamp.Request,
            P,
            R,
            HttpMessageAndTimestamp.Response,
            T
        >(
            connectionId,
            eventLoop,
            configuration.clock(),
            configuration.nanoTime(),
            configuration.replayTimeMapper(),
            configuration.requestPreparerFactory().apply(connectionId, connectionContext),
            configuration.retryPolicy(),
            configuration.targetChannelFactory().create(
                connectionId,
                eventLoop,
                connectionContext
            ),
            tupleWriterWorker.writer,
            configuration.tupleFactory(),
            configuration.resourceReleaser(),
            permitProvider,
            assemblySink.new IntakeLifecycleSink(connectionId),
            eventLoopOwnedFailure(
                eventLoop,
                "target connection owner " + connectionId,
                "owner transition"
            )::accept,
            org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.CountHook.NOOP,
            configuration.maximumResponseRetries(),
            () -> assemblySink.releaseTerminatedConnection(
                connectionId,
                Objects.requireNonNull(
                    terminalBinding.get(),
                    "connection binding was not installed before owner termination"
                )
            )
        );
        var binding = new ConnectionBinding(owner, connectionContext);
        terminalBinding.set(binding);
        return binding;
    }

    private void releaseRejectedAdmission(
        IReplayContexts.IRequestContext requestContext,
        HttpMessageAndTimestamp.Request request,
        Throwable failure
    ) {
        Throwable cleanupFailure = null;
        try {
            requestContext.close();
        } catch (Throwable contextFailure) {
            cleanupFailure = contextFailure;
        }
        try {
            configuration.resourceReleaser().releaseSourceRequest(request);
        } catch (Throwable releaseFailure) {
            if (cleanupFailure == null) {
                cleanupFailure = releaseFailure;
            } else if (cleanupFailure != releaseFailure) {
                cleanupFailure.addSuppressed(releaseFailure);
            }
        }
        if (cleanupFailure != null) {
            if (cleanupFailure != failure) {
                cleanupFailure.addSuppressed(failure);
            }
            reportUnexpectedFailure(
                "replay composition root",
                "rejected request admission cleanup " + requestContext.getRequestId(),
                cleanupFailure
            );
        }
    }

    private void releaseConnectionResources(
        ConnectionProcessingId connectionId,
        ConnectionBinding binding
    ) {
        contextManager.releaseContextFor(binding.connectionContext);
    }

    private void closeTupleWriterWorkers() {
        for (var worker : tupleWriterWorkers) {
            worker.closeOnOwner();
            if (fatalTerminationStarted.get()) {
                return;
            }
        }
    }

    private final class TupleWriterWorker {
        private final int workerIndex;
        private final EventLoop eventLoop;
        private final ManagedTupleTransformer<T> transformer;
        private final ManagedPhysicalTupleSink<T> sink;
        private final TupleWriter<T> writer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TupleWriterWorker(
            int workerIndex,
            EventLoop eventLoop,
            ManagedTupleTransformer<T> transformer,
            ManagedPhysicalTupleSink<T> sink
        ) {
            this.workerIndex = workerIndex;
            this.eventLoop = eventLoop;
            this.transformer = transformer;
            this.sink = sink;
            this.writer = new TupleWriter<>(
                eventLoop,
                configuration.clock(),
                configuration.tupleRetryDelayPolicy(),
                transformer,
                sink,
                configuration.tupleReleaser(),
                eventLoopOwnedFailure(
                    eventLoop,
                    "tuple writer worker " + workerIndex,
                    "owner transition"
                )::accept,
                org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.CountHook.NOOP
            );
        }

        private void closeOnOwner() {
            if (closed.get()) {
                return;
            }
            if (eventLoop.inEventLoop()) {
                closeComponents();
                return;
            }
            try {
                configuration.ownerTaskRunner().runAndWait(eventLoop, this::closeComponents);
            } catch (Throwable failure) {
                reportUnexpectedFailure(
                    "tuple writer worker " + workerIndex,
                    "close submission",
                    failure
                );
            }
        }

        private void closeComponents() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Throwable failure = null;
            try {
                transformer.close();
            } catch (Throwable transformerFailure) {
                failure = transformerFailure;
            }
            try {
                sink.close();
            } catch (Throwable sinkFailure) {
                if (failure == null) {
                    failure = sinkFailure;
                } else if (failure != sinkFailure) {
                    failure.addSuppressed(sinkFailure);
                }
            }
            if (failure != null) {
                reportUnexpectedFailure(
                    "tuple writer worker " + workerIndex,
                    "close",
                    failure
                );
            }
        }

        private void closeAfterConstructionFailure(Throwable constructionFailure) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closePartiallyConstructed(
                transformer,
                "tuple transformer",
                workerIndex,
                constructionFailure
            );
            closePartiallyConstructed(
                sink,
                "tuple sink",
                workerIndex,
                constructionFailure
            );
        }
    }

    private final class ConnectionBinding {
        private final TargetConnectionOwner<
            HttpMessageAndTimestamp.Request,
            P,
            R,
            HttpMessageAndTimestamp.Response,
            T
        > owner;
        private final IReplayContexts.IConnectionContext connectionContext;
        private volatile boolean intakeRemovalAccepted;

        private ConnectionBinding(
            TargetConnectionOwner<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response,
                T
            > owner,
            IReplayContexts.IConnectionContext connectionContext
        ) {
            this.owner = owner;
            this.connectionContext = connectionContext;
        }
    }

    private final class ConnectionAssemblySink implements SourceAssemblySink {
        private final Function<Instant, Instant> replayTimeMapper;
        private final ConcurrentHashMap<ConnectionProcessingId, ConnectionBinding> connections =
            new ConcurrentHashMap<>();

        private ConnectionAssemblySink(Function<Instant, Instant> replayTimeMapper) {
            this.replayTimeMapper = replayTimeMapper;
        }

        @Override
        public void onRequestReconstituted(
            ReplayRequestId requestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant requestFirstByteSourceTime,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime
        ) {
            throw new IllegalStateException(
                "production request delivery requires its replay context"
            );
        }

        @Override
        public void onRequestReconstituted(
            HttpMessageAndTimestamp.Request request,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime,
            IReplayContexts.IRequestContext requestContext
        ) {
            var requestId = requestContext.getRequestId();
            var connectionId = requestContext.getConnectionProcessingId();
            var binding = connections.get(connectionId);
            var newBinding = binding == null;
            if (binding == null) {
                binding = createConnection(this, connectionId);
            }
            var admission = new TargetConnectionOwner.AdmitReconstitutedRequest<
                HttpMessageAndTimestamp.Request,
                HttpMessageAndTimestamp.Response
            >(
                request,
                requestContext
            );
            final CompletionStage<TargetConnectionOwner.RequestAdmissionResult> accepted;
            if (newBinding) {
                try {
                    accepted = binding.owner.submitForPublication(admission);
                } catch (RuntimeException | Error failure) {
                    releaseRejectedAdmission(requestContext, request, failure);
                    releaseUnpublishedConnection(connectionId, binding);
                    return;
                }
                connections.put(connectionId, binding);
            } else {
                accepted = binding.owner.submit(admission);
            }
            accepted.whenComplete((result, failure) -> {
                if (failure != null) {
                    releaseRejectedAdmission(requestContext, request, failure);
                } else if (result instanceof TargetConnectionOwner.RequestAdmissionRejected rejected) {
                    releaseRejectedAdmission(
                        requestContext,
                        request,
                        rejected.cause()
                    );
                }
            });
        }

        @Override
        public void onSourceInterimResponse(
            ReplayRequestId requestId,
            HttpMessageAndTimestamp.InterimResponse interimResponse
        ) {
            // Replay intake owns the exact source-interim bytes and their record associations. The deployed
            // tuple contract consumes only the final source response, so this source-observation callback has
            // no target-owner message to send. In particular, do not route it through target interim handling.
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId requestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            var connectionId = requestId.connectionProcessingId();
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.FinalSourceResponseComplete<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(
                    connectionId,
                    connectionId.generation(),
                    requestId,
                    response,
                    keptAlive
                )
            );
        }

        @Override
        public void onRetrySourceResponseComplete(
            ReplayRequestId requestId,
            HttpMessageAndTimestamp.Response response
        ) {
            var connectionId = requestId.connectionProcessingId();
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.RetrySourceResponseComplete<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(connectionId, connectionId.generation(), requestId, response)
            );
        }

        @Override
        public void onSourceResponseUnavailableForRetry(ReplayRequestId requestId) {
            var connectionId = requestId.connectionProcessingId();
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.SourceResponseUnavailableForRetry<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(connectionId, connectionId.generation(), requestId)
            );
        }

        @Override
        public void onSourceResponseIncomplete(
            ReplayRequestId requestId,
            IncompleteReason reason
        ) {
            var connectionId = requestId.connectionProcessingId();
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.FinalSourceResponseIncomplete<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(connectionId, connectionId.generation(), requestId, reason.name())
            );
        }

        @Override
        public void onCapturedConnectionExpired(ConnectionProcessingId connectionId) {
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.CapturedConnectionExpired<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(connectionId, connectionId.generation())
            );
        }

        @Override
        public void onGracefulGenerationCancellation(
            ConnectionProcessingId connectionId,
            CancellationGrace grace
        ) {
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.GracefulConnectionCancellation<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(
                    connectionId,
                    connectionId.generation(),
                    grace,
                    new CancellationException("partition generation entered " + grace)
                )
            );
        }

        @Override
        public void onForceGenerationCancellation(ConnectionProcessingId connectionId) {
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.ForceConnectionCancellation<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(
                    connectionId,
                    connectionId.generation(),
                    new CancellationException("partition generation force-cancelled")
                )
            );
        }

        @Override
        public void onCapturedClose(
            ConnectionProcessingId connectionId,
            long capturedOrdinal,
            Instant closeTime
        ) {
            requireConnection(connectionId).owner.submit(
                new TargetConnectionOwner.AdmitCapturedClose<
                    HttpMessageAndTimestamp.Request,
                    HttpMessageAndTimestamp.Response
                >(connectionId, connectionId.generation(), capturedOrdinal,
                    replayTimeMapper.apply(closeTime))
            );
        }

        @Override
        public void onConnectionOwnerFinished(ConnectionProcessingId connectionId) {
            var binding = connections.remove(connectionId);
            if (binding == null) {
                throw new IllegalStateException("connection owner was not published for " + connectionId);
            }
            binding.intakeRemovalAccepted = true;
        }

        private ConnectionBinding requireConnection(ConnectionProcessingId connectionId) {
            var binding = connections.get(connectionId);
            if (binding == null) {
                throw new IllegalStateException("no published connection owner for " + connectionId);
            }
            return binding;
        }

        private void releaseTerminatedConnection(
            ConnectionProcessingId connectionId,
            ConnectionBinding binding
        ) {
            if (!binding.intakeRemovalAccepted) {
                reportUnexpectedFailure(
                    "target connection owner " + connectionId,
                    "termination before intake removal",
                    new IllegalStateException(
                        "Connection owner terminated before intake removed " + connectionId
                    )
                );
            }
            releaseConnectionResources(connectionId, binding);
        }

        private void releaseUnpublishedConnection(
            ConnectionProcessingId connectionId,
            ConnectionBinding binding
        ) {
            releaseConnectionResources(connectionId, binding);
        }

        private final class IntakeLifecycleSink implements TargetConnectionOwner.LifecycleSink {
            private final ConnectionProcessingId connectionId;

            private IntakeLifecycleSink(ConnectionProcessingId connectionId) {
                this.connectionId = connectionId;
            }

            @Override
            public CompletionStage<Void> connectionRequestFinished(
                org.opensearch.migrations.replay.identity.PartitionGenerationId generation,
                ConnectionProcessingId reportedConnectionId,
                ReplayRequestId requestId
            ) {
                requireLifecycleIdentity(reportedConnectionId);
                return intakeInputs.submitAndAwaitHandling(
                    new RequestLifecycleInput.ConnectionRequestFinished(generation, requestId)
                );
            }

            @Override
            public CompletionStage<Void> requestProcessingFinished(
                org.opensearch.migrations.replay.identity.PartitionGenerationId generation,
                ConnectionProcessingId reportedConnectionId,
                ReplayRequestId requestId
            ) {
                requireLifecycleIdentity(reportedConnectionId);
                return intakeInputs.submitAndAwaitHandling(
                    new RequestLifecycleInput.RequestProcessingFinished(generation, requestId)
                );
            }

            @Override
            public CompletionStage<Void> connectionOwnerFinished(
                org.opensearch.migrations.replay.identity.PartitionGenerationId generation,
                ConnectionProcessingId reportedConnectionId
            ) {
                requireLifecycleIdentity(reportedConnectionId);
                return intakeInputs.submitAndAwaitHandling(
                    new ReplayIntakeInput.ConnectionOwnerFinished(generation, connectionId)
                );
            }

            @Override
            public CompletionStage<Void> connectionCleanupFinished(
                org.opensearch.migrations.replay.identity.PartitionGenerationId generation,
                ConnectionProcessingId reportedConnectionId
            ) {
                requireLifecycleIdentity(reportedConnectionId);
                return intakeInputs.submitAndAwaitHandling(
                    new ReplayIntakeInput.ConnectionCleanupFinished(generation, connectionId)
                );
            }

            private void requireLifecycleIdentity(ConnectionProcessingId reportedConnectionId) {
                if (!connectionId.equals(reportedConnectionId)) {
                    throw new IllegalArgumentException(
                        "lifecycle result for " + reportedConnectionId
                            + " cannot route through " + connectionId
                    );
                }
            }
        }

    }
}
