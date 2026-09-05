package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.StreamLifecycleManager;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory<RecordMetadata>, AutoCloseable {

    public static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    public static final Duration DEFAULT_LIVENESS_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_ROUTING_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(1);
    static final long DEFAULT_PENDING_CAPTURE_BYTES = 64L * 1024 * 1024;
    static final int DEFAULT_PENDING_CAPTURE_RECORDS = 4096;
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;
    private static final int ROUTING_STAMP_RESERVE_BYTES = 128;

    private final IRootKafkaOffloaderContext rootScope;
    private final String nodeId;
    private final String topicNameForTraffic;
    private final int bufferSize;
    private final int deferredBufferSize;
    private final Object routingInitializationLock = new Object();
    private final CompletableFuture<CaptureKafkaPublisher> publisherFuture;
    private final ProxyLivenessRegistry livenessRegistry;
    private final Set<String> connectionsAwaitingRouting = new HashSet<>();
    private final PendingCaptureBudget pendingCaptureBudget;
    private final Producer<String, byte[]> producer;
    private final Integer requestedShardWidth;
    private final Duration livenessSnapshotInterval;
    private final Duration routingDiscoveryRetryDelay;
    private final ScheduledThreadPoolExecutor routingInitializer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CaptureKafkaPublisher publisher;
    private volatile Throwable routingInitializationFailure;
    private int routingDiscoveryFailures;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize
    ) {
        this(
            rootScope,
            nodeId,
            producer,
            topicNameForTraffic,
            messageSize,
            null,
            DEFAULT_LIVENESS_SNAPSHOT_INTERVAL
        );
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize,
        Integer requestedShardWidth,
        Duration livenessSnapshotInterval
    ) {
        this(
            rootScope,
            nodeId,
            producer,
            topicNameForTraffic,
            messageSize,
            requestedShardWidth,
            livenessSnapshotInterval,
            DEFAULT_ROUTING_DISCOVERY_RETRY_DELAY,
            DEFAULT_PENDING_CAPTURE_BYTES,
            DEFAULT_PENDING_CAPTURE_RECORDS
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize,
        Integer requestedShardWidth,
        Duration livenessSnapshotInterval,
        Duration routingDiscoveryRetryDelay,
        long maximumPendingCaptureBytes,
        int maximumPendingCaptureRecords
    ) {
        this.rootScope = Objects.requireNonNull(rootScope);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.producer = Objects.requireNonNull(producer);
        this.topicNameForTraffic = Objects.requireNonNull(topicNameForTraffic);
        this.requestedShardWidth = requestedShardWidth;
        this.livenessSnapshotInterval = requirePositive(livenessSnapshotInterval, "livenessSnapshotInterval");
        this.routingDiscoveryRetryDelay = requirePositive(
            routingDiscoveryRetryDelay,
            "routingDiscoveryRetryDelay"
        );
        this.bufferSize = checkedPayloadSize(messageSize);
        this.deferredBufferSize = bufferSize - ROUTING_STAMP_RESERVE_BYTES;
        if (deferredBufferSize <= 0) {
            throw new IllegalArgumentException("messageSize is too small for a deferred routing stamp");
        }
        this.publisherFuture = new CompletableFuture<>();
        this.livenessRegistry = new ProxyLivenessRegistry();
        this.pendingCaptureBudget = new PendingCaptureBudget(
            maximumPendingCaptureBytes,
            maximumPendingCaptureRecords
        );
        this.routingInitializer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-routing-initializer");
            thread.setDaemon(true);
            return thread;
        });
        routingInitializer.setRemoveOnCancelPolicy(true);
        routingInitializer.execute(this::discoverRoutingPlan);
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        String topicNameForTraffic,
        int messageSize,
        CaptureKafkaPublisher publisher
    ) {
        this.rootScope = Objects.requireNonNull(rootScope);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.topicNameForTraffic = Objects.requireNonNull(topicNameForTraffic);
        this.bufferSize = checkedPayloadSize(messageSize);
        this.deferredBufferSize = bufferSize;
        this.publisher = Objects.requireNonNull(publisher);
        this.publisherFuture = CompletableFuture.completedFuture(publisher);
        this.livenessRegistry = publisher.getLivenessRegistry();
        this.pendingCaptureBudget = new PendingCaptureBudget(
            DEFAULT_PENDING_CAPTURE_BYTES,
            DEFAULT_PENDING_CAPTURE_RECORDS
        );
        this.producer = null;
        this.requestedShardWidth = null;
        this.livenessSnapshotInterval = null;
        this.routingDiscoveryRetryDelay = null;
        this.routingInitializer = null;
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        int messageSize
    ) {
        this(rootScope, nodeId, producer, DEFAULT_TOPIC_NAME_FOR_TRAFFIC, messageSize);
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
        synchronized (routingInitializationLock) {
            if (closed.get()) {
                throw new IllegalStateException("Kafka capture factory is closed");
            }
            var readyPublisher = publisher;
            if (readyPublisher == null) {
                if (routingInitializationFailure == null && !connectionsAwaitingRouting.add(connectionId)) {
                    throw new IllegalStateException(
                        "Connection " + connectionId + " is already waiting for Kafka routing"
                    );
                }
                try {
                    return new StreamChannelConnectionCaptureSerializer<>(
                        nodeId,
                        connectionId,
                        new DeferredStreamManager(ctx, connectionId)
                    );
                } catch (RuntimeException | Error t) {
                    connectionsAwaitingRouting.remove(connectionId);
                    throw t;
                }
            }
            return createRoutedOffloader(ctx, connectionId, readyPublisher);
        }
    }

    private IChannelConnectionCaptureSerializer<RecordMetadata> createRoutedOffloader(
        IConnectionContext ctx,
        String connectionId,
        CaptureKafkaPublisher readyPublisher
    ) {
        int partition = readyPublisher.getRoutingPlan().partitionFor(connectionId);
        livenessRegistry.register(connectionId, partition);
        try {
            return new StreamChannelConnectionCaptureSerializer<>(
                nodeId,
                connectionId,
                partition,
                readyPublisher.getRoutingPlan().getRoutingPlanId(),
                new StreamManager(ctx, connectionId, partition)
            );
        } catch (RuntimeException | Error t) {
            livenessRegistry.remove(connectionId, partition);
            throw t;
        }
    }

    private void discoverRoutingPlan() {
        if (closed.get()) {
            return;
        }
        try {
            var routingPlan = PartitionRoutingPlan.discover(
                producer,
                topicNameForTraffic,
                nodeId,
                requestedShardWidth
            );
            finishRoutingInitialization(routingPlan);
        } catch (IllegalArgumentException e) {
            failRoutingInitialization(e);
        } catch (RuntimeException e) {
            retryRoutingDiscovery(e);
        }
    }

    private void finishRoutingInitialization(PartitionRoutingPlan routingPlan) {
        CaptureKafkaPublisher initializedPublisher;
        synchronized (routingInitializationLock) {
            if (closed.get() || routingInitializationFailure != null) {
                return;
            }
            for (var connectionId : connectionsAwaitingRouting) {
                livenessRegistry.register(connectionId, routingPlan.partitionFor(connectionId));
            }
            initializedPublisher = new CaptureKafkaPublisher(
                producer,
                topicNameForTraffic,
                nodeId,
                routingPlan,
                livenessRegistry,
                bufferSize + KAFKA_MESSAGE_OVERHEAD_BYTES,
                livenessSnapshotInterval
            );
            publisher = initializedPublisher;
            connectionsAwaitingRouting.clear();
        }
        publisherFuture.complete(initializedPublisher);
        routingInitializer.shutdown();
        log.atInfo()
            .setMessage("Initialized Kafka capture routing after {} failed metadata attempt(s): {}")
            .addArgument(routingDiscoveryFailures)
            .addArgument(routingPlan)
            .log();
    }

    private void retryRoutingDiscovery(RuntimeException failure) {
        if (closed.get()) {
            return;
        }
        routingDiscoveryFailures++;
        if (routingDiscoveryFailures == 1) {
            log.atWarn()
                .setCause(failure)
                .setMessage("Kafka routing metadata is unavailable; capture is buffering while discovery retries")
                .log();
        } else {
            log.atDebug()
                .setCause(failure)
                .setMessage("Kafka routing metadata remains unavailable; retry={}")
                .addArgument(routingDiscoveryFailures)
                .log();
        }
        if (!routingInitializer.isShutdown()) {
            try {
                routingInitializer.schedule(
                    this::discoverRoutingPlan,
                    routingDiscoveryRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                if (!closed.get() && routingInitializationFailure == null) {
                    throw e;
                }
            }
        }
    }

    private void failRoutingInitialization(Throwable failure) {
        synchronized (routingInitializationLock) {
            if (routingInitializationFailure != null || publisher != null) {
                return;
            }
            routingInitializationFailure = failure;
            connectionsAwaitingRouting.clear();
        }
        publisherFuture.completeExceptionally(failure);
        if (routingInitializer != null) {
            routingInitializer.shutdownNow();
        }
        log.atError()
            .setCause(failure)
            .setMessage("Kafka capture routing failed closed; no authoritative liveness snapshots will be emitted")
            .log();
    }

    private void failCapture(Throwable failure) {
        var readyPublisher = publisher;
        if (readyPublisher == null) {
            failRoutingInitialization(failure);
        } else {
            readyPublisher.failClosed(failure);
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

    private static byte[] stampRouting(
        byte[] payload,
        int partition,
        String routingPlanId
    ) throws InvalidProtocolBufferException {
        return TrafficStream.parseFrom(payload)
            .toBuilder()
            .setPartition(partition)
            .setRoutingPlanId(routingPlanId)
            .build()
            .toByteArray();
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

    class DeferredStreamManager implements StreamLifecycleManager<RecordMetadata> {
        private final IConnectionContext telemetryContext;
        private final String connectionId;
        private CompletableFuture<RecordMetadata> futureForLastClose = CompletableFuture.completedFuture(null);
        private Throwable terminalFailure;

        DeferredStreamManager(IConnectionContext telemetryContext, String connectionId) {
            this.telemetryContext = telemetryContext;
            this.connectionId = connectionId;
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            telemetryContext.addEvent("streamCreatedBeforeKafkaRouting");
            ByteBuffer bb = ByteBuffer.allocate(deferredBufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public synchronized CompletableFuture<RecordMetadata> closeStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            var unstampedPayload = payload(outputStreamHolder);
            var readyPublisher = publisher;
            if (readyPublisher != null) {
                futureForLastClose = futureForLastClose.thenCompose(
                    ignored -> publishDeferredPayload(readyPublisher, unstampedPayload, index)
                );
                return futureForLastClose;
            }
            if (!pendingCaptureBudget.tryReserve(unstampedPayload.length)) {
                terminalFailure = new IllegalStateException(
                    "Kafka routing initialization buffer is full; bytes="
                        + pendingCaptureBudget.currentBytes()
                        + ", records="
                        + pendingCaptureBudget.currentRecords()
                );
                failCapture(terminalFailure);
                return CompletableFuture.failedFuture(terminalFailure);
            }
            var publisherAfterRouting = publisherFuture.whenComplete(
                (ignored, failure) -> pendingCaptureBudget.release(unstampedPayload.length)
            );
            futureForLastClose = futureForLastClose
                .thenCompose(ignored -> publisherAfterRouting.thenCompose(initializedPublisher -> publishDeferredPayload(
                    initializedPublisher,
                    unstampedPayload,
                    index
                )));
            return futureForLastClose;
        }

        private CompletableFuture<RecordMetadata> publishDeferredPayload(
            CaptureKafkaPublisher readyPublisher,
            byte[] unstampedPayload,
            int index
        ) {
            int partition = readyPublisher.getRoutingPlan().partitionFor(connectionId);
            try {
                var finalRecord = isFinalRecord(unstampedPayload);
                var stampedPayload = stampRouting(
                    unstampedPayload,
                    partition,
                    readyPublisher.getRoutingPlan().getRoutingPlanId()
                );
                return publishPayload(
                    telemetryContext,
                    connectionId,
                    partition,
                    stampedPayload,
                    finalRecord,
                    index,
                    readyPublisher
                );
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private static class PendingCaptureBudget {
        private final long maximumBytes;
        private final int maximumRecords;
        private long currentBytes;
        private int currentRecords;

        PendingCaptureBudget(long maximumBytes, int maximumRecords) {
            if (maximumBytes <= 0 || maximumRecords <= 0) {
                throw new IllegalArgumentException("Pending capture limits must be positive");
            }
            this.maximumBytes = maximumBytes;
            this.maximumRecords = maximumRecords;
        }

        synchronized boolean tryReserve(int bytes) {
            if (bytes < 0
                || bytes > maximumBytes - currentBytes
                || currentRecords + 1 > maximumRecords) {
                return false;
            }
            currentBytes += bytes;
            currentRecords++;
            return true;
        }

        synchronized void release(int bytes) {
            currentBytes -= bytes;
            currentRecords--;
        }

        synchronized long currentBytes() {
            return currentBytes;
        }

        synchronized int currentRecords() {
            return currentRecords;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (routingInitializer != null) {
            routingInitializer.shutdownNow();
        }
        var readyPublisher = publisher;
        if (readyPublisher != null) {
            readyPublisher.close();
            return;
        }
        publisherFuture.completeExceptionally(new IllegalStateException("Kafka capture factory closed before routing"));
        producer.close(Duration.ZERO);
    }
}
