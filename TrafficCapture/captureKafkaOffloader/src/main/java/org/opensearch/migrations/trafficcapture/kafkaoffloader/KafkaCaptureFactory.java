package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory<RecordMetadata>, AutoCloseable {

    public static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    public static final Duration DEFAULT_LIVENESS_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(1);
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;
    private final IRootKafkaOffloaderContext rootScope;
    private final String nodeId;
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
    private final ScheduledThreadPoolExecutor initializer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CaptureKafkaPublisher publisher;
    private volatile CaptureKafkaPublisher initializingPublisher;
    private volatile CaptureKafkaMembership membership;
    private final AtomicReference<CaptureKafkaWriteGate> writeGate = new AtomicReference<>();
    private final AtomicReference<Throwable> initializationFailure = new AtomicReference<>();
    private int topicMetadataDiscoveryFailures;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        String topicNameForTraffic,
        int messageSize,
        Duration livenessSnapshotInterval
    ) {
        this(
            rootScope,
            nodeId,
            producer,
            membershipConsumer,
            assignmentTracker,
            minimumActiveProxyCount,
            topicNameForTraffic,
            messageSize,
            livenessSnapshotInterval,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            ignored -> {}
        );
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        String topicNameForTraffic,
        int messageSize,
        Duration livenessSnapshotInterval,
        java.util.function.Consumer<Throwable> captureFailureCallback
    ) {
        this(
            rootScope,
            nodeId,
            producer,
            membershipConsumer,
            assignmentTracker,
            minimumActiveProxyCount,
            topicNameForTraffic,
            messageSize,
            livenessSnapshotInterval,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            captureFailureCallback
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        CaptureMembershipAssignmentTracker assignmentTracker,
        int minimumActiveProxyCount,
        String topicNameForTraffic,
        int messageSize,
        Duration livenessSnapshotInterval,
        Duration topicMetadataDiscoveryRetryDelay,
        java.util.function.Consumer<Throwable> captureFailureCallback
    ) {
        this.rootScope = Objects.requireNonNull(rootScope);
        this.nodeId = Objects.requireNonNull(nodeId);
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
        IllegalStateException unavailableBeforeAssignment = null;
        synchronized (initializationLock) {
            if (closed.get()) {
                throw new IllegalStateException("Kafka capture factory is closed");
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
            }
        }
        if (unavailableBeforeAssignment != null) {
            failCapture(unavailableBeforeAssignment);
            throw unavailableBeforeAssignment;
        }
        return createRoutedOffloader(ctx, connectionId, readyPublisher);
    }

    private IChannelConnectionCaptureSerializer<RecordMetadata> createRoutedOffloader(
        IConnectionContext ctx,
        String connectionId,
        CaptureKafkaPublisher readyPublisher
    ) {
        int partition = readyPublisher.getRoutingState().admitConnection(connectionId);
        try {
            return new StreamChannelConnectionCaptureSerializer<>(
                nodeId,
                connectionId,
                partition,
                new StreamManager(ctx, connectionId, partition)
            );
        } catch (RuntimeException | Error t) {
            readyPublisher.removeConnectionRegistration(connectionId, partition);
            throw t;
        }
    }

    private void discoverTopicMetadata() {
        if (closed.get()) {
            return;
        }
        try {
            var topicMetadata = TrafficTopicMetadata.discover(producer, topicNameForTraffic);
            finishTopicMetadataInitialization(topicMetadata);
        } catch (IllegalArgumentException e) {
            failCapture(e);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e);
        }
    }

    private void finishTopicMetadataInitialization(TrafficTopicMetadata topicMetadata) {
        startMembershipInitialization(topicMetadata);
    }

    private void startMembershipInitialization(TrafficTopicMetadata topicMetadata) {
        CaptureKafkaMembership initializedMembership;
        synchronized (initializationLock) {
            if (closed.get() || initializationFailure.get() != null) {
                return;
            }
            var routingState = new CaptureRoutingState(
                topicMetadata.getTopicPartitionCount(),
                List.of()
            );
            var createdWriteGate = new CaptureKafkaWriteGate();
            var createdPublisher = new CaptureKafkaPublisher(
                producer,
                topicNameForTraffic,
                nodeId,
                routingState,
                bufferSize + KAFKA_MESSAGE_OVERHEAD_BYTES,
                livenessSnapshotInterval,
                java.time.Clock.systemUTC(),
                createdWriteGate
            );
            writeGate.set(createdWriteGate);
            initializingPublisher = createdPublisher;
            initializedMembership = new CaptureKafkaMembership(
                membershipConsumer,
                topicNameForTraffic,
                routingState,
                createdPublisher,
                createdWriteGate,
                assignmentTracker,
                minimumActiveProxyCount,
                this::finishMembershipInitialization,
                this::failCapture,
                this::failCapture
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
                if (closed.get() || initializationFailure.get() != null || publisher != null) {
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
        }
    }

    private void retryTopicMetadataDiscovery(RuntimeException failure) {
        if (closed.get()) {
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
                    this::discoverTopicMetadata,
                    topicMetadataDiscoveryRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                if (!closed.get() && initializationFailure.get() == null) {
                    throw e;
                }
            }
        }
    }

    private void failCapture(Throwable failure) {
        CaptureKafkaPublisher publisherToFail;
        boolean closeInitializingPublisher;
        synchronized (initializationLock) {
            if (initializationFailure.get() != null) {
                return;
            }
            initializationFailure.set(failure);
            publisherToFail = publisher == null ? initializingPublisher : publisher;
            closeInitializingPublisher = publisher == null && initializingPublisher != null;
            if (!closeInitializingPublisher) {
                initializingPublisher = null;
            }
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
        captureFailureCallback.accept(failure);
        if (closeInitializingPublisher) {
            var closeThread = new Thread(
                publisherToFail::close,
                "failed-capture-kafka-publisher-close"
            );
            closeThread.setDaemon(true);
            closeThread.start();
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

    private static boolean isFinalRecord(byte[] payload) throws InvalidProtocolBufferException {
        return TrafficStream.parseFrom(payload).hasNumberOfThisLastChunk();
    }

    private CompletableFuture<RecordMetadata> publishPayload(
        IConnectionContext telemetryContext,
        String connectionId,
        int partition,
        byte[] payload,
        boolean finalRecord,
        int index,
        CaptureKafkaPublisher readyPublisher
    ) {
        String recordId = String.format("%s.%d", connectionId, index);
        var flushContext = rootScope.createKafkaRecordContext(
            telemetryContext,
            topicNameForTraffic,
            recordId,
            payload.length
        );
        return readyPublisher.publishTraffic(connectionId, partition, payload, finalRecord)
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
        String connectionId;
        int partition;

        public StreamManager(
            IConnectionContext ctx,
            String connectionId,
            int partition
        ) {
            // TODO - add https://opentelemetry.io/blog/2022/instrument-kafka-clients/
            this.telemetryContext = ctx;
            this.connectionId = connectionId;
            this.partition = partition;
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
                    connectionId,
                    partition,
                    recordPayload,
                    isFinalRecord(recordPayload),
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
                try {
                    readyPublisher.prepareForGracefulShutdown()
                        .get(CaptureKafkaPublisher.CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.atWarn()
                        .setCause(e)
                        .setMessage("Unable to publish graceful Kafka writer-completion declarations")
                        .log();
                } finally {
                    readyMembership.close();
                }
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
        try {
            membershipConsumer.close(Duration.ZERO);
        } finally {
            producer.close(Duration.ZERO);
        }
    }
}
