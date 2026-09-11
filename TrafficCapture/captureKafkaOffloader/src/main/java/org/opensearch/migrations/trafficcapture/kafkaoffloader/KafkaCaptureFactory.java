package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IOrderlyRetirableCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficRecord;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;

@Slf4j
public class KafkaCaptureFactory implements
    IConnectionCaptureFactory<RecordMetadata>,
    IOrderlyRetirableCaptureFactory,
    AutoCloseable {

    public static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    public static final Duration DEFAULT_LIVENESS_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(1);
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;
    private final IRootKafkaOffloaderContext rootScope;
    private final String captureActivationId;
    private final String topicNameForTraffic;
    private final int bufferSize;
    private final Object initializationLock = new Object();
    private final CompletableFuture<CaptureKafkaPublisher> publisherFuture;
    private final Producer<String, byte[]> producer;
    private final Consumer<String, byte[]> membershipConsumer;
    private final CaptureMembershipAssignmentTracker assignmentTracker;
    private final int minimumActiveProxyCount;
    private final Duration livenessSnapshotInterval;
    private final Duration topicMetadataDiscoveryRetryDelay;
    private final java.util.function.Consumer<Throwable> captureFailureCallback;
    private final java.util.function.Consumer<Throwable> unstableProcessFailureCallback;
    private final ScheduledThreadPoolExecutor initializer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean untransferredKafkaResourcesClosed = new AtomicBoolean();
    private final AtomicBoolean unstableFailureReported = new AtomicBoolean();
    private volatile CaptureKafkaPublisher publisher;
    private volatile CaptureKafkaPublisher initializingPublisher;
    private volatile CaptureKafkaMembership membership;
    private volatile CompletableFuture<Void> orderlyRetirement;
    private volatile boolean producerLifecycleTransferredToPublisher;
    private final AtomicReference<CaptureKafkaWriteGate> writeGate = new AtomicReference<>();
    private final AtomicReference<Throwable> initializationFailure = new AtomicReference<>();
    private int topicMetadataDiscoveryFailures;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        String topicNameForTraffic,
        int messageSize,
        Duration livenessSnapshotInterval,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            rootScope,
            captureActivationId,
            producer,
            membershipConsumer,
            assignmentTracker,
            minimumActiveProxyCount,
            topicNameForTraffic,
            messageSize,
            livenessSnapshotInterval,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        String topicNameForTraffic,
        int messageSize,
        Duration livenessSnapshotInterval,
        Duration topicMetadataDiscoveryRetryDelay,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.rootScope = Objects.requireNonNull(rootScope);
        this.captureActivationId = Objects.requireNonNull(captureActivationId);
        this.producer = Objects.requireNonNull(producer);
        this.membershipConsumer = Objects.requireNonNull(membershipConsumer);
        this.assignmentTracker = Objects.requireNonNull(assignmentTracker);
        if (minimumActiveProxyCount <= 0) {
            throw new IllegalArgumentException("minimumActiveProxyCount must be positive");
        }
        this.minimumActiveProxyCount = minimumActiveProxyCount;
        this.topicNameForTraffic = Objects.requireNonNull(topicNameForTraffic);
        this.livenessSnapshotInterval = requirePositive(livenessSnapshotInterval, "livenessSnapshotInterval");
        this.topicMetadataDiscoveryRetryDelay = requirePositive(
            topicMetadataDiscoveryRetryDelay,
            "topicMetadataDiscoveryRetryDelay"
        );
        this.captureFailureCallback = Objects.requireNonNull(captureFailureCallback);
        this.unstableProcessFailureCallback = Objects.requireNonNull(unstableProcessFailureCallback);
        this.bufferSize = checkedPayloadSize(messageSize);
        this.publisherFuture = new CompletableFuture<>();
        this.initializer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-initializer");
            thread.setDaemon(true);
            return thread;
        });
        initializer.setRemoveOnCancelPolicy(true);
        initializer.execute(this::discoverTopicMetadata);
    }

    public CaptureKafkaPublisher getPublisher() {
        return publisherFuture.join();
    }

    CompletableFuture<CaptureKafkaPublisher> publisherReady() {
        return publisherFuture;
    }

    @Override
    public IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(IConnectionContext ctx) {
        var connectionId = Objects.requireNonNull(
            ctx.getConnectionId(),
            "connectionId must not be null - partition locality requires a stable key"
        );
        CaptureKafkaPublisher readyPublisher;
        CaptureRoutingState.ConnectionRoute route = null;
        IllegalStateException unavailableBeforeAssignment = null;
        synchronized (initializationLock) {
            if (closed.get()) {
                throw new IllegalStateException("Kafka capture factory is closed");
            }
            if (orderlyRetirement != null) {
                throw new IllegalStateException("Kafka capture factory is retiring for orderly shutdown");
            }
            var terminalFailure = initializationFailure.get();
            if (terminalFailure != null) {
                throw new IllegalStateException("Kafka capture is permanently unavailable", terminalFailure);
            }
            readyPublisher = publisher;
            if (readyPublisher == null) {
                unavailableBeforeAssignment = new IllegalStateException(
                    "Kafka capture is not accepting new connections before its first group assignment"
                );
            } else {
                route = readyPublisher.getRoutingState().admitConnection(connectionId);
            }
        }
        if (unavailableBeforeAssignment != null) {
            failCapture(unavailableBeforeAssignment);
            throw unavailableBeforeAssignment;
        }
        return createRoutedOffloader(ctx, Objects.requireNonNull(route), readyPublisher);
    }

    private IChannelConnectionCaptureSerializer<RecordMetadata> createRoutedOffloader(
        IConnectionContext ctx,
        CaptureRoutingState.ConnectionRoute route,
        CaptureKafkaPublisher readyPublisher
    ) {
        try {
            return new StreamChannelConnectionCaptureSerializer<>(
                route.writerNodeId(),
                route.connectionId(),
                route.partition(),
                route::manifestCycle,
                new StreamManager(ctx, route)
            );
        } catch (Error t) {
            readyPublisher.abandonUnpublishedConnection(route);
            failUnstable(t);
            throw t;
        } catch (RuntimeException t) {
            readyPublisher.abandonUnpublishedConnection(route);
            throw t;
        }
    }

    private void discoverTopicMetadata() {
        if (closed.get() || orderlyRetirement != null) {
            return;
        }
        try {
            var topicMetadata = TrafficTopicMetadata.discover(producer, topicNameForTraffic);
            publishStartupCapabilityProbes(topicMetadata);
        } catch (IllegalArgumentException e) {
            failCapture(e);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, this::discoverTopicMetadata);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void publishStartupCapabilityProbes(TrafficTopicMetadata topicMetadata) {
        CaptureKafkaCapabilityProbe.publish(
            producer,
            topicNameForTraffic,
            captureActivationId,
            topicMetadata.getRepresentativePartitionsByLeader()
        ).whenComplete((ignored, failure) -> {
            if (closed.get() || orderlyRetirement != null) {
                return;
            }
            if (failure != null) {
                handleKafkaFailure(unwrapCompletionFailure(failure));
                return;
            }
            try {
                initializer.execute(this::refreshTopicMetadataAfterProbe);
            } catch (RejectedExecutionException e) {
                if (!closed.get() && orderlyRetirement == null && initializationFailure.get() == null) {
                    failUnstable(e);
                }
            }
        });
    }

    private void refreshTopicMetadataAfterProbe() {
        if (closed.get() || orderlyRetirement != null) {
            return;
        }
        try {
            startMembershipInitialization(
                TrafficTopicMetadata.discover(producer, topicNameForTraffic)
            );
        } catch (IllegalArgumentException e) {
            failCapture(e);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, this::refreshTopicMetadataAfterProbe);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void startMembershipInitialization(TrafficTopicMetadata topicMetadata) {
        CaptureKafkaMembership initializedMembership;
        synchronized (initializationLock) {
            if (closed.get() || orderlyRetirement != null || initializationFailure.get() != null) {
                return;
            }
            var routingState = new CaptureRoutingState(
                captureActivationId,
                topicMetadata.getTopicPartitionCount()
            );
            var createdWriteGate = new CaptureKafkaWriteGate();
            var createdPublisher = new CaptureKafkaPublisher(
                producer,
                topicNameForTraffic,
                routingState,
                bufferSize + KAFKA_MESSAGE_OVERHEAD_BYTES,
                livenessSnapshotInterval,
                java.time.Clock.systemUTC(),
                createdWriteGate,
                this::failUnstable
            );
            writeGate.set(createdWriteGate);
            initializingPublisher = createdPublisher;
            producerLifecycleTransferredToPublisher = true;
            createdWriteGate.addTerminalFailureListener(this::handleKafkaFailure);
            initializedMembership = new CaptureKafkaMembership(
                membershipConsumer,
                topicNameForTraffic,
                routingState,
                createdPublisher,
                assignmentTracker,
                minimumActiveProxyCount,
                this::finishMembershipInitialization,
                this::failCapture,
                this::failUnstable
            );
            membership = initializedMembership;
        }
        initializer.shutdown();
        initializedMembership.start();
        log.atInfo()
            .setMessage("Kafka capture metadata is ready; waiting for the first proxy-group assignment")
            .log();
    }

    private void finishMembershipInitialization() {
        CaptureKafkaPublisher initializedPublisher;
        try {
            var gateFailure = Objects.requireNonNull(writeGate.get()).failureIfNotWritable();
            if (gateFailure != null) {
                failCapture(gateFailure);
                return;
            }
            synchronized (initializationLock) {
                if (closed.get()
                    || orderlyRetirement != null
                    || initializationFailure.get() != null
                    || publisher != null) {
                    return;
                }
                initializedPublisher = Objects.requireNonNull(initializingPublisher);
                publisher = initializedPublisher;
                initializingPublisher = null;
            }
            publisherFuture.complete(initializedPublisher);
            log.atInfo()
                .setMessage("Initialized Kafka capture from proxy-group assignment {}")
                .addArgument(initializedPublisher.getRoutingState().assignedPartitions())
                .log();
        } catch (RuntimeException e) {
            failCapture(e);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void retryTopicMetadataDiscovery(RuntimeException failure, Runnable retryAction) {
        if (closed.get() || orderlyRetirement != null) {
            return;
        }
        topicMetadataDiscoveryFailures++;
        if (topicMetadataDiscoveryFailures == 1) {
            log.atWarn()
                .setCause(failure)
                .setMessage("Kafka topic metadata is unavailable; capture initialization will retry")
                .log();
        } else {
            log.atDebug()
                .setCause(failure)
                .setMessage("Kafka topic metadata remains unavailable; retry={}")
                .addArgument(topicMetadataDiscoveryFailures)
                .log();
        }
        if (!initializer.isShutdown()) {
            try {
                initializer.schedule(
                    retryAction,
                    topicMetadataDiscoveryRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                if (!closed.get() && orderlyRetirement == null && initializationFailure.get() == null) {
                    failUnstable(e);
                }
            }
        }
    }

    /**
     * Stops assignment changes and waits until all captured connections and assignment-scoped
     * writers have completed the orderly retirement protocol. The caller remains responsible for
     * process-level warning and termination deadlines and for subsequently calling {@link #close()}.
     */
    @Override
    public CompletableFuture<Void> retireForOrderlyShutdown() {
        final CompletableFuture<Void> result;
        final CaptureKafkaMembership membershipToClose;
        final CaptureKafkaPublisher publisherToRetire;
        synchronized (initializationLock) {
            if (orderlyRetirement != null) {
                return orderlyRetirement;
            }
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Kafka capture factory is already closed")
                );
            }
            var terminalFailure = initializationFailure.get();
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            result = new CompletableFuture<>();
            orderlyRetirement = result;
            membershipToClose = membership;
            publisherToRetire = publisher == null ? initializingPublisher : publisher;
        }

        initializer.shutdownNow();
        var membershipStopped = membershipToClose == null
            ? CompletableFuture.<Void>completedFuture(null)
            : membershipToClose.closeAsync();
        var membershipCallbacksStopped = membershipStopped.handle((ignored, failure) -> {
            if (failure != null) {
                log.atWarn()
                    .setCause(unwrapCompletionFailure(failure))
                    .setMessage(
                        "Kafka membership did not close cleanly during orderly shutdown; "
                            + "continuing capture retirement after membership callbacks stopped"
                    )
                    .log();
            }
            return null;
        });

        if (publisherToRetire == null) {
            membershipCallbacksStopped.whenComplete((ignored, failure) -> result.complete(null));
            return result;
        }
        CompletableFuture.allOf(
            membershipCallbacksStopped,
            publisherToRetire.getRoutingState().whenNoConnections()
        )
            .thenCompose(ignored -> publisherToRetire.retireAllWriters())
            .whenComplete((ignored, failure) -> {
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(unwrapCompletionFailure(failure));
                }
            });
        return result;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private void handleKafkaFailure(Throwable failure) {
        if (failure instanceof Error) {
            failUnstable(failure);
        } else {
            failCapture(failure);
        }
    }

    private void failCapture(Throwable failure) {
        failAndCloseCapture(failure, true);
    }

    private void failUnstable(Throwable failure) {
        if (unstableFailureReported.compareAndSet(false, true)) {
            log.atError()
                .setCause(failure)
                .setMessage("Kafka capture process ownership is unstable; terminating the process")
                .log();
            unstableProcessFailureCallback.accept(failure);
        }
        failAndCloseCapture(failure, false);
    }

    private void failAndCloseCapture(Throwable failure, boolean notifyCaptureFailurePolicy) {
        CaptureKafkaPublisher publisherToFail;
        CaptureKafkaMembership membershipToClose;
        synchronized (initializationLock) {
            if (initializationFailure.get() != null) {
                return;
            }
            initializationFailure.set(failure);
            publisherToFail = publisher == null ? initializingPublisher : publisher;
            initializingPublisher = null;
            membershipToClose = membership;
        }
        if (publisherToFail != null) {
            publisherToFail.failClosed(failure);
        }
        publisherFuture.completeExceptionally(failure);
        if (initializer != null) {
            initializer.shutdownNow();
        }
        log.atError()
            .setCause(failure)
            .setMessage("Kafka capture is permanently unavailable in this process")
            .log();
        if (notifyCaptureFailurePolicy) {
            captureFailureCallback.accept(failure);
        }
        if (publisherToFail != null || membershipToClose != null) {
            var closeThread = new Thread(
                () -> closeFailedCaptureResources(membershipToClose, publisherToFail),
                "failed-capture-kafka-publisher-close"
            );
            closeThread.setDaemon(true);
            closeThread.start();
        } else {
            var closeThread = new Thread(
                this::closeUntransferredKafkaResources,
                "failed-capture-kafka-resource-close"
            );
            closeThread.setDaemon(true);
            closeThread.start();
        }
    }

    private static void closeFailedCaptureResources(
        CaptureKafkaMembership membership,
        CaptureKafkaPublisher publisher
    ) {
        if (membership != null) {
            membership.close();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    private static int checkedPayloadSize(int messageSize) {
        if (messageSize <= KAFKA_MESSAGE_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("messageSize is too small for Kafka record overhead");
        }
        return messageSize - KAFKA_MESSAGE_OVERHEAD_BYTES;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;

        @Override
        public int getOutputStreamBytesLimit() {
            return byteBuffer.limit();
        }

        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    private byte[] payload(CodedOutputStreamHolder outputStreamHolder) {
        if (!(outputStreamHolder instanceof CodedOutputStreamWrapper osh)) {
            throw new IllegalArgumentException(
                "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
            );
        }
        return Arrays.copyOfRange(osh.byteBuffer.array(), 0, osh.byteBuffer.position());
    }

    private boolean isTerminalRecord(byte[] payload) throws InvalidProtocolBufferException {
        var record = TrafficRecord.parseFrom(payload);
        var finalChunk = record.hasNumberOfThisLastChunk();
        var closeCount = record.getObservationsList()
            .stream()
            .filter(observation -> observation.hasClose())
            .count();
        var closeIsLast = closeCount == 1
            && record.getObservations(record.getObservationsCount() - 1).hasClose();
        if (finalChunk != closeIsLast) {
            var failure = new IllegalStateException(
                "A connection's final Kafka record must contain exactly one terminal CloseObservation as its last observation"
            );
            failUnstable(failure);
            throw failure;
        }
        return finalChunk;
    }

    private CompletableFuture<RecordMetadata> publishPayload(
        IConnectionContext telemetryContext,
        CaptureRoutingState.ConnectionRoute route,
        byte[] payload,
        boolean finalRecord,
        int index,
        CaptureKafkaPublisher readyPublisher
    ) {
        String recordId = String.format("%s.%d", route.connectionId(), index);
        var flushContext = rootScope.createKafkaRecordContext(
            telemetryContext,
            topicNameForTraffic,
            recordId,
            payload.length
        );
        return readyPublisher.publishTraffic(route, payload, finalRecord)
            .whenComplete((recordMetadata, throwable) -> {
                if (throwable != null) {
                    flushContext.addTraceException(throwable, true);
                    log.error("Error sending producer record: {}", recordId, throwable);
                } else {
                    log.debug(
                        "Kafka producer record: {} has finished sending for topic: {} and partition {}",
                        recordId,
                        recordMetadata.topic(),
                        recordMetadata.partition()
                    );
                }
                flushContext.close();
            });
    }

    class StreamManager extends OrderedStreamLifecyleManager<RecordMetadata> {
        IConnectionContext telemetryContext;
        CaptureRoutingState.ConnectionRoute route;

        public StreamManager(
            IConnectionContext ctx,
            CaptureRoutingState.ConnectionRoute route
        ) {
            // TODO - add https://opentelemetry.io/blog/2022/instrument-kafka-clients/
            this.telemetryContext = ctx;
            this.route = route;
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            telemetryContext.addEvent("streamCreated");

            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public CompletableFuture<RecordMetadata> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            try {
                var recordPayload = payload(outputStreamHolder);
                return publishPayload(
                    telemetryContext,
                    route,
                    recordPayload,
                    isTerminalRecord(recordPayload),
                    index,
                    publisher
                );
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (initializer != null) {
            initializer.shutdownNow();
        }
        // Routing initialization decides whether to build a publisher while holding this lock and after
        // checking the flag set above, so close has to take the lock to read the result of that decision.
        // Reading it unlocked can observe no publisher while one is being constructed, which would leave
        // it running -- with its liveness snapshot timer -- against the producer closed just below.
        CaptureKafkaPublisher readyPublisher;
        CaptureKafkaPublisher publisherStillInitializing;
        CaptureKafkaMembership readyMembership;
        synchronized (initializationLock) {
            readyPublisher = publisher;
            publisherStillInitializing = initializingPublisher;
            readyMembership = membership;
        }
        if (readyPublisher != null) {
            if (readyMembership != null) {
                readyMembership.close();
            }
            readyPublisher.close();
            return;
        }
        if (readyMembership != null) {
            readyMembership.close();
        }
        if (publisherStillInitializing != null) {
            publisherStillInitializing.close();
            return;
        }
        publisherFuture.completeExceptionally(new IllegalStateException("Kafka capture factory closed before initialization"));
        if (!producerLifecycleTransferredToPublisher) {
            closeUntransferredKafkaResources();
        }
    }

    private void closeUntransferredKafkaResources() {
        if (!untransferredKafkaResourcesClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            membershipConsumer.close(Duration.ZERO);
        } finally {
            producer.close(Duration.ZERO);
        }
    }
}
