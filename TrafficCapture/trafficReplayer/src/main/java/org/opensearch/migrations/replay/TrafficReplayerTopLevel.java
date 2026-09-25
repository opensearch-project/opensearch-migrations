package org.opensearch.migrations.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;
import org.opensearch.migrations.replay.kafkasource.GraceIntervalWait;
import org.opensearch.migrations.replay.kafkasource.KafkaConsumerSourcePort;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationCancelled;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.TargetChannelPort;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.sink.TupleWriter.PhysicalTupleSink;
import org.opensearch.migrations.replay.sink.TupleWriter.TransformedTuple;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleDropped;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleTransformer;
import org.opensearch.migrations.replay.sink.TupleSink;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;

/**
 * G5's production composition root. G9 owns starting and supervising its two owner loops.
 *
 * <p>The Kafka adapter, both owner queues, replay intake, process-local connection registry,
 * connection/request owners, tuple writer and context lifecycle are constructed as one reachable chain.
 * Every connection is published only after its first event-loop submission has been accepted.</p>
 */
public final class TrafficReplayerTopLevel<P extends AutoCloseable, R, T>
    implements AutoCloseable {

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
        ManagedTupleTransformer<T> create(ConnectionProcessingId connectionProcessingId);
    }

    public interface ManagedTupleTransformer<T> extends TupleTransformer<T>, AutoCloseable {
        @Override
        void close() throws Exception;
    }

    @FunctionalInterface
    public interface ManagedPhysicalTupleSinkFactory<T> {
        ManagedPhysicalTupleSink<T> create(ConnectionProcessingId connectionProcessingId);
    }

    public interface ManagedPhysicalTupleSink<T> extends PhysicalTupleSink<T>, AutoCloseable {
        @Override
        void close() throws Exception;
    }

    public record Configuration<P extends AutoCloseable, R, T>(
        @NonNull Clock clock,
        @NonNull LongSupplier nanoTime,
        @NonNull Function<Instant, Instant> replayTimeMapper,
        @NonNull Function<ConnectionProcessingId, EventLoop> eventLoopFor,
        @NonNull BiFunction<
            ConnectionProcessingId,
            IReplayContexts.IConnectionContext,
            RequestReplayOwner.RequestPreparer<HttpMessageAndTimestamp.Request, P>
        > requestPreparerFactory,
        @NonNull RequestReplayOwner.RetryPolicy<R, HttpMessageAndTimestamp.Response> retryPolicy,
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
        public Configuration {
            if (tupleRetryDelay.isNegative()) {
                throw new IllegalArgumentException("tupleRetryDelay must not be negative");
            }
            if (maximumTargetAttempts <= 0) {
                throw new IllegalArgumentException("maximumTargetAttempts must be positive");
            }
            if (maximumResponseRetries <= 0) {
                throw new IllegalArgumentException("maximumResponseRetries must be positive");
            }
        }
    }

    private final KafkaSourceInputQueue sourceInputs;
    private final ReplayIntakeInputQueue intakeInputs;
    private final KafkaSourceOwner sourceOwner;
    private final ReplayIntakeOwner intakeOwner;
    private final ConnectionAssemblySink connectionAssemblySink;
    private final Configuration<P, R, T> configuration;
    private final ChannelContextManager contextManager;
    private final TargetAttemptPermitProvider permitProvider;
    private final Consumer<Error> fatalHandler;
    private boolean intakeStarted;

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // TrafficReplayerTopLevel.<init>(7-argument) -> TrafficReplayerTopLevel.<init>(6-argument)
    // TrafficReplayerTopLevel.<init>(8-argument) -> TrafficReplayerTopLevel.<init>(6-argument)
    // REBUILD-TRACE-END(G5,target)
    public TrafficReplayerTopLevel(
        @NonNull org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
        @NonNull RootReplayerContext rootContext,
        @NonNull Configuration<P, R, T> configuration,
        @NonNull Duration kafkaPollTimeout,
        @NonNull Duration cancellationGrace,
        @NonNull Consumer<Error> fatalHandler
    ) {
        this.configuration = configuration;
        this.fatalHandler = fatalHandler;
        this.contextManager = new ChannelContextManager(rootContext);
        var wakeupController = new WakeupController(consumer::wakeup, rootContext);
        this.sourceInputs = new KafkaSourceInputQueue(wakeupController);
        this.intakeInputs = new ReplayIntakeInputQueue();
        this.permitProvider = new TargetAttemptPermitProvider(
            configuration.maximumTargetAttempts(),
            new AtomicInteger(),
            rootContext.getTargetAttemptPermitMetrics(),
            fatalHandler::accept,
            configuration.nanoTime()
        );
        this.connectionAssemblySink = new ConnectionAssemblySink(
            configuration.replayTimeMapper()
        );
        this.intakeOwner = new ReplayIntakeOwner(
            intakeInputs,
            sourceInputs,
            connectionAssemblySink,
            fatalHandler::accept,
            rootContext.getReplayIntakeMetrics(),
            ReplayIntakeOwner.RecordObserver.NOOP,
            configuration.brokerTimeConfiguration()
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
            rootContext::createKafkaRecordContext
        );
    }

    public void startIntake() {
        if (intakeStarted) {
            throw new IllegalStateException("replay intake already started");
        }
        intakeStarted = true;
        intakeOwner.start();
    }

    public void runSourceOnce() {
        sourceOwner.runOnce();
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

    int activeConnectionCount() {
        return connectionAssemblySink.connections.size();
    }

    java.util.List<org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot>
    activitySnapshot(ConnectionProcessingId connectionProcessingId) {
        return connectionAssemblySink.requireConnection(connectionProcessingId)
            .owner.activitySnapshot();
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
                    fatalHandler.accept(new Error(
                        "Kafka revocation callback was interrupted",
                        interrupted
                    ));
                }
            }

            @Override
            public void onPartitionsAssigned(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions
            ) {
                sourceOwner.onPartitionsAssigned(partitions);
            }

            @Override
            public void onPartitionsLost(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions
            ) {
                sourceOwner.onPartitionsLost(partitions);
            }
        };
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // ThreadLocalTupleWriter.<init>(IntFunction,Supplier) ->
    //     TrafficReplayerTopLevel.deployedTupleTransformer
    // REBUILD-TRACE-END(G5,target)
    public static ManagedTupleTransformer<Map<String, Object>> deployedTupleTransformer(
        @NonNull Supplier<IJsonTransformer> transformerSupplier
    ) {
        var transformer = Objects.requireNonNull(
            transformerSupplier.get(),
            "tuple transformer supplier returned null"
        );
        return new ManagedTupleTransformer<>() {
            @Override
            public TupleWriter.TupleTransformation<Map<String, Object>> transform(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                final Object transformed;
                try {
                    transformed = transformer.transformJson(tuple);
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
                transformer.close();
            }
        };
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // RequestTransformerAndSender.transformAllData -> TrafficReplayerTopLevel.deployedRequestPreparer
    //     [feed captured packets through request transformation and finalize the transformed request].
    // REBUILD-TRACE-END(G5,target)
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
                        completion.completeExceptionally(terminalFailure);
                        return;
                    }
                    if (transformed == null) {
                        completion.completeExceptionally(new NullPointerException(
                            "request transformation completed without a result"
                        ));
                        return;
                    }
                    var prepared = transformed.transformedOutput == null
                        ? null
                        : new NettyPacketToHttpConsumer.PreparedRequest(
                            transformed.transformedOutput,
                            packetInterval
                        );
                    var result = new RequestPreparationReady<>(
                        prepared,
                        transformed.transformationStatus
                    );
                    if (!completion.complete(result) && prepared != null) {
                        prepared.close();
                    }
                });
            } catch (Throwable failure) {
                try {
                    transformer.close();
                } catch (Throwable closeFailure) {
                    if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                completion.completeExceptionally(
                    TrackedFuture.unwindPossibleCompletionException(failure)
                );
            }
            return preparationOperation(completion, settled);
        };
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

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // TrafficReplayerCore.TrafficReplayerAccumulationCallbacks.packageAndWriteTuple ->
    //     TrafficReplayerTopLevel.deployedTupleFactory
    // REBUILD-TRACE-END(G5,target)
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
            public void close() {
                sink.close();
            }
        };
    }

    @Override
    public void close() {
        sourceInputs.close();
        if (intakeStarted && intakeInputs.requestStopAfterDraining()) {
            intakeOwner.termination().toCompletableFuture().join();
        } else if (!intakeStarted) {
            intakeInputs.closeNow();
        }
    }

    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // RequestSenderOrchestrator.<init>(ClientConnectionPool,BiFunction) ->
    //     TrafficReplayerTopLevel.createConnection
    // RequestSenderOrchestrator.<init>(ClientConnectionPool,Duration,Duration,BiFunction) ->
    //     TrafficReplayerTopLevel.createConnection
    // ThreadLocalTupleWriter.<init>(IntFunction) -> TrafficReplayerTopLevel.createConnection
    // ThreadLocalTupleWriter.<init>(IntFunction,Supplier) -> TrafficReplayerTopLevel.createConnection
    // TrafficReplayerTopLevel.<init>(7-argument) -> TrafficReplayerTopLevel.createConnection
    // TrafficReplayerTopLevel.<init>(8-argument) -> TrafficReplayerTopLevel.createConnection
    // REBUILD-TRACE-END(G5,target)
    private ConnectionBinding createConnection(
        ConnectionAssemblySink assemblySink,
        ConnectionProcessingId connectionId
    ) {
        var eventLoop = Objects.requireNonNull(
            configuration.eventLoopFor().apply(connectionId),
            "event-loop selector returned null"
        );
        var connectionContext = contextManager.apply(connectionId);
        var tupleTransformer = Objects.requireNonNull(
            configuration.tupleTransformerFactory().create(connectionId),
            "tuple transformer factory returned null"
        );
        var tupleSink = Objects.requireNonNull(
            configuration.tupleSinkFactory().create(connectionId),
            "tuple sink factory returned null"
        );
        var tupleWriter = new TupleWriter<>(
            eventLoop,
            configuration.clock(),
            configuration.tupleRetryDelay(),
            tupleTransformer,
            tupleSink,
            configuration.tupleReleaser(),
            fatalHandler::accept,
            org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.CountHook.NOOP
        );
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
            tupleWriter,
            configuration.tupleFactory(),
            configuration.resourceReleaser(),
            permitProvider,
            assemblySink.new IntakeLifecycleSink(connectionId),
            fatalHandler::accept,
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
        var binding = new ConnectionBinding(
            owner,
            connectionContext,
            tupleTransformer,
            tupleSink
        );
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
            fatalHandler.accept(new Error(
                "Rejected request admission cleanup failed for "
                    + requestContext.getRequestId(),
                cleanupFailure
            ));
        }
    }

    // REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
    // ThreadLocalTupleWriter.close -> TrafficReplayerTopLevel.releaseConnectionResources
    // REBUILD-TRACE-END(G5,source)
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // ThreadLocalTupleWriter.close -> TrafficReplayerTopLevel.releaseConnectionResources
    // REBUILD-TRACE-END(G5,target)
    private void releaseConnectionResources(
        ConnectionProcessingId connectionId,
        ConnectionBinding binding
    ) {
        closeManaged(binding.tupleTransformer, "tuple transformer", connectionId);
        closeManaged(binding.tupleSink, "tuple sink", connectionId);
        contextManager.releaseContextFor(binding.connectionContext);
    }

    // REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
    // ThreadLocalTupleWriter.close -> TrafficReplayerTopLevel.closeManaged
    // REBUILD-TRACE-END(G5,source)
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // ThreadLocalTupleWriter.close -> TrafficReplayerTopLevel.closeManaged
    // REBUILD-TRACE-END(G5,target)
    private void closeManaged(
        AutoCloseable closeable,
        String component,
        ConnectionProcessingId connectionId
    ) {
        try {
            closeable.close();
        } catch (Exception failure) {
            fatalHandler.accept(new Error(
                "Failed to close " + component + " for " + connectionId,
                failure
            ));
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
        private final ManagedTupleTransformer<T> tupleTransformer;
        private final ManagedPhysicalTupleSink<T> tupleSink;
        private volatile boolean intakeRemovalAccepted;

        private ConnectionBinding(
            TargetConnectionOwner<
                HttpMessageAndTimestamp.Request,
                P,
                R,
                HttpMessageAndTimestamp.Response,
                T
            > owner,
            IReplayContexts.IConnectionContext connectionContext,
            ManagedTupleTransformer<T> tupleTransformer,
            ManagedPhysicalTupleSink<T> tupleSink
        ) {
            this.owner = owner;
            this.connectionContext = connectionContext;
            this.tupleTransformer = tupleTransformer;
            this.tupleSink = tupleSink;
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
                fatalHandler.accept(new Error(
                    "Connection owner terminated before intake removed " + connectionId
                ));
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
                    new ReplayIntakeInput.ConnectionRequestFinished(generation, requestId)
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
                    new ReplayIntakeInput.RequestProcessingFinished(generation, requestId)
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

// REBUILD-LIMBO(G5) -- the carried predecessor members below remain inert. Javadoc is left outside
// the marked regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: BlockingTrafficSource CapturedTrafficToHttpTransactionAccumulator ClientConnectionPool IRootReplayerContext NettyPacketToHttpConsumer . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

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

*/
// REBUILD-LIMBO-END(G5)
    /** Returns the current accumulator, or null if not yet initialized. */
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.getCurrentAccumulator -> RETIRED
//     [connLLD sections 2-4 assign live mutable source and connection state to their owners;
//     Plan A G5 forbids restoring the predecessor source-owned connection registry].
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
    public CapturedTrafficToHttpTransactionAccumulator getCurrentAccumulator() {
        return currentAccumulator.get();
    }

*/
// REBUILD-LIMBO-END(G5)
    /** Returns the current replay engine, or null if not yet initialized. */
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker.put ->
//     OutstandingOperationRegistry.register
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
        @Override
        public void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, TrackedFuture<String, T> completableFuture) {
            map.put(uniqueReplayerRequestKey, completableFuture);
        }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker.remove ->
//     OutstandingOperationRegistry.complete
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
        @Override
        public void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey) {
            map.remove(uniqueReplayerRequestKey);
        }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker.isEmpty ->
//     OutstandingOperationRegistry.activeCount
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker.size ->
//     OutstandingOperationRegistry.activeCount
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
        @Override
        public int size() {
            return map.size();
        }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker.getRemainingItems ->
//     OutstandingOperationRegistry.snapshots
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// TrafficReplayerTopLevel.<init>(7-argument) -> TrafficReplayerTopLevel.<init>(6-argument)
// TrafficReplayerTopLevel.<init>(8-argument) -> TrafficReplayerTopLevel.<init>(6-argument)
// TrafficReplayerTopLevel.<init>(7-argument) -> TrafficReplayerTopLevel.createConnection
// TrafficReplayerTopLevel.<init>(8-argument) -> TrafficReplayerTopLevel.createConnection
// The two baseline overloads were consolidated into the retained ten-argument predecessor below.
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
    /** Legacy overload: uses the old synchronous Consumer-based tuple path (Log4J). */
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
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
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
    /** Legacy overload: uses the old synchronous Consumer-based tuple path (Log4J). */
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
    /**
     * A shutdown that cannot finish is otherwise silent, which leaves nothing to diagnose from. Name
     * the stage that hasn't finished along with the work it is still waiting on.
     */
// REBUILD-LIMBO-START(G5)
/*
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

*/
// REBUILD-LIMBO-END(G5)
