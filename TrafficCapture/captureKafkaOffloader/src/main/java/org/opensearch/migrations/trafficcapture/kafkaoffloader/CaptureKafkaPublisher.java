package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.ProxyNoMoreWrites;

import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Owns the ordered Kafka submission lane for traffic and authoritative liveness declarations.
 */
@Slf4j
public class CaptureKafkaPublisher implements AutoCloseable {
    public static final String RECORD_TYPE_HEADER = CaptureRecordTypes.RECORD_TYPE_HEADER;
    public static final String TRAFFIC_RECORD_TYPE = CaptureRecordTypes.TRAFFIC_RECORD_TYPE;
    public static final String LIVENESS_RECORD_TYPE = CaptureRecordTypes.LIVENESS_RECORD_TYPE;
    public static final String NO_MORE_WRITES_RECORD_TYPE =
        CaptureRecordTypes.NO_MORE_WRITES_RECORD_TYPE;

    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final String nodeId;
    @Getter
    private final PartitionRoutingPlan routingPlan;
    @Getter
    private final CaptureRoutingState routingState;
    private final CaptureKafkaWriteGate writeGate;
    private final int payloadSizeLimit;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<Integer, Long> nextSnapshotSequence = new HashMap<>();
    private final Map<Integer, Long> lastControlTimestamp = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object inFlightLock = new Object();
    private final Set<CompletableFuture<RecordMetadata>> inFlightSends =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final ScheduledFuture<?> scheduledSnapshots;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        PartitionRoutingPlan routingPlan,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval
    ) {
        this(
            producer,
            topic,
            nodeId,
            routingPlan,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            Clock.systemUTC(),
            CaptureKafkaWriteGate.unrestricted()
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        PartitionRoutingPlan routingPlan,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock
    ) {
        this(
            producer,
            topic,
            nodeId,
            routingPlan,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            clock,
            CaptureKafkaWriteGate.unrestricted()
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        PartitionRoutingPlan routingPlan,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock,
        CaptureKafkaWriteGate writeGate
    ) {
        this.producer = Objects.requireNonNull(producer);
        this.topic = Objects.requireNonNull(topic);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.routingPlan = Objects.requireNonNull(routingPlan);
        this.routingState = Objects.requireNonNull(routingState);
        if (routingState.topicPartitionCount() != routingPlan.getTopicPartitionCount()) {
            throw new IllegalArgumentException("Routing state and routing plan describe different topics");
        }
        this.clock = Objects.requireNonNull(clock);
        this.writeGate = Objects.requireNonNull(writeGate);
        if (maximumKafkaMessageSize <= KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("maximumKafkaMessageSize is too small for Kafka record overhead");
        }
        payloadSizeLimit = maximumKafkaMessageSize - KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES;
        if (snapshotInterval.isZero() || snapshotInterval.isNegative()) {
            throw new IllegalArgumentException("snapshotInterval must be positive");
        }
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-publisher");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        scheduledSnapshots = executor.scheduleWithFixedDelay(
            this::publishScheduledSnapshot,
            snapshotInterval.toMillis(),
            snapshotInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        writeGate.addTerminalFailureListener(this::failPublisher);
    }

    public CompletableFuture<RecordMetadata> publishTraffic(
        String connectionId,
        int partition,
        byte[] payload,
        boolean finalRecord
    ) {
        final int registeredPartition;
        try {
            registeredPartition = routingState.partitionFor(connectionId);
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (registeredPartition != partition) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    "Connection "
                        + connectionId
                        + " is registered for partition "
                        + registeredPartition
                        + ", not "
                        + partition
                )
            );
        }
        var producerRecord = new ProducerRecord<>(
            topic,
            partition,
            null,
            connectionId,
            payload.clone(),
            recordHeaders(TRAFFIC_RECORD_TYPE)
        );
        return enqueueSend(
            producerRecord,
            finalRecord
                ? () -> routingState.remove(connectionId, partition).ifPresent(this::publishSelfNoMoreWrites)
                : () -> {}
        );
    }

    public CompletableFuture<Void> publishLivenessSnapshotNow() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            var sends = new ArrayList<CompletableFuture<RecordMetadata>>();
            for (var partition : routingState.partitionsForSnapshot()) {
                var sequence = nextSnapshotSequence.merge(partition, 1L, Long::sum) - 1;
                var emittedAtMillis = allocateControlTimestamp(partition);
                var chunks = buildSnapshotChunks(
                    partition,
                    sequence,
                    emittedAtMillis,
                    routingState.snapshot(partition)
                );
                for (var chunk : chunks) {
                    var key = nodeId + ":liveness:" + partition;
                    var producerRecord = new ProducerRecord<>(
                        topic,
                        partition,
                        null,
                        key,
                        chunk.toByteArray(),
                        recordHeaders(LIVENESS_RECORD_TYPE)
                    );
                    sends.add(sendFromPublisherThread(producerRecord, () -> {}));
                }
            }
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(throwable);
                    }
                });
        }, result);
        return result;
    }

    public CompletableFuture<RecordMetadata> publishNoMoreWrites(
        String finishedNodeId,
        int partition,
        String declaredBy
    ) {
        if (finishedNodeId == null || finishedNodeId.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("finishedNodeId must not be blank")
            );
        }
        if (declaredBy == null || declaredBy.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("declaredBy must not be blank")
            );
        }
        if (partition < 0 || partition >= routingPlan.getTopicPartitionCount()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("partition is outside the traffic topic")
            );
        }
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() -> {
            sendFromPublisherThread(noMoreWritesRecord(finishedNodeId, partition, declaredBy), () -> {})
                .whenComplete((metadata, throwable) -> {
                    completeFrom(metadata, throwable, result);
                });
        }, result);
        return result;
    }

    CompletableFuture<RecordMetadata> publishSelfNoMoreWrites(CaptureRoutingState.SelfRelease release) {
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() -> {
            boolean submitted = routingState.submitSelfReleaseIfCurrent(release, () ->
                sendFromPublisherThread(
                    noMoreWritesRecord(nodeId, release.partition(), nodeId),
                    () -> routingState.completeSelfRelease(release)
                ).whenComplete((metadata, throwable) -> completeFrom(metadata, throwable, result))
            );
            if (!submitted) {
                result.complete(null);
            }
        }, result);
        return result;
    }

    void removeConnectionRegistration(String connectionId, int partition) {
        routingState.remove(connectionId, partition).ifPresent(this::publishSelfNoMoreWrites);
    }

    CompletableFuture<Void> prepareForGracefulShutdown() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            producer.flush();
            executeInternal(() -> publishShutdownDeclarations(result), result);
        }, result);
        return result;
    }

    private void publishShutdownDeclarations(CompletableFuture<Void> result) {
        try {
            var sends = routingState.beginGracefulShutdown()
                .stream()
                .map(partition ->
                    sendFromPublisherThread(noMoreWritesRecord(nodeId, partition, nodeId), () -> {})
                )
                .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(sends)
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(throwable);
                    }
                });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
    }

    private ProducerRecord<String, byte[]> noMoreWritesRecord(
        String finishedNodeId,
        int partition,
        String declaredBy
    ) {
        var declaration = ProxyNoMoreWrites.newBuilder()
            .setNodeId(finishedNodeId)
            .setPartition(partition)
            .setDeclaredBy(declaredBy)
            .setEmittedAtMillis(allocateControlTimestamp(partition))
            .build();
        return new ProducerRecord<>(
            topic,
            partition,
            null,
            finishedNodeId + ":no-more-writes:" + partition,
            declaration.toByteArray(),
            recordHeaders(NO_MORE_WRITES_RECORD_TYPE)
        );
    }

    private static <T> void completeFrom(
        T value,
        Throwable throwable,
        CompletableFuture<T> result
    ) {
        if (throwable == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(throwable);
        }
    }

    List<ProxyLivenessSnapshotChunk> buildSnapshotChunks(
        int partition,
        long sequence,
        long emittedAtMillis,
        List<String> openConnections
    ) {
        var chunkConnections = new ArrayList<List<ByteString>>();
        var current = new ArrayList<ByteString>();
        for (var connection : openConnections) {
            var encoded = ByteString.copyFromUtf8(connection);
            var candidate = new ArrayList<>(current);
            candidate.add(encoded);
            if (estimatedChunkSize(partition, sequence, emittedAtMillis, candidate) <= payloadSizeLimit) {
                current.add(encoded);
            } else {
                if (current.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Connection identity is too large for a liveness snapshot record"
                    );
                }
                chunkConnections.add(List.copyOf(current));
                current.clear();
                current.add(encoded);
            }
        }
        if (!current.isEmpty() || chunkConnections.isEmpty()) {
            chunkConnections.add(List.copyOf(current));
        }

        int chunkCount = chunkConnections.size();
        var chunks = new ArrayList<ProxyLivenessSnapshotChunk>(chunkCount);
        for (int i = 0; i < chunkCount; ++i) {
            var chunk = baseSnapshotChunk(partition, sequence, emittedAtMillis)
                .setChunkIndex(i)
                .setChunkCount(chunkCount)
                .addAllOpenConnections(chunkConnections.get(i))
                .build();
            if (chunk.getSerializedSize() > payloadSizeLimit) {
                throw new IllegalStateException("Liveness snapshot chunk exceeds Kafka payload limit");
            }
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private long allocateControlTimestamp(int partition) {
        var observed = clock.millis();
        var previous = lastControlTimestamp.get(partition);
        var allocated = previous == null || observed > previous ? observed : Math.incrementExact(previous);
        lastControlTimestamp.put(partition, allocated);
        return allocated;
    }

    private int estimatedChunkSize(
        int partition,
        long sequence,
        long emittedAtMillis,
        List<ByteString> connections
    ) {
        return baseSnapshotChunk(partition, sequence, emittedAtMillis)
            .setChunkIndex(Integer.MAX_VALUE)
            .setChunkCount(Integer.MAX_VALUE)
            .addAllOpenConnections(connections)
            .build()
            .getSerializedSize();
    }

    private ProxyLivenessSnapshotChunk.Builder baseSnapshotChunk(
        int partition,
        long sequence,
        long emittedAtMillis
    ) {
        return ProxyLivenessSnapshotChunk.newBuilder()
            .setNodeId(nodeId)
            .setPartition(partition)
            .setRoutingPlanId(routingPlan.getRoutingPlanId())
            .setSnapshotSequence(sequence)
            .setEmittedAtMillis(emittedAtMillis);
    }

    private CompletableFuture<RecordMetadata> enqueueSend(
        ProducerRecord<String, byte[]> producerRecord,
        Runnable acknowledgedAction
    ) {
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() -> sendFromPublisherThread(producerRecord, acknowledgedAction)
            .whenComplete((metadata, throwable) -> {
                if (throwable == null) {
                    result.complete(metadata);
                } else {
                    result.completeExceptionally(throwable);
                }
            }), result);
        return result;
    }

    private CompletableFuture<RecordMetadata> sendFromPublisherThread(
        ProducerRecord<String, byte[]> producerRecord,
        Runnable acknowledgedAction
    ) {
        var result = new CompletableFuture<RecordMetadata>();
        var publisherRejection = new AtomicReference<Throwable>();
        Throwable gateRejection;
        try {
            gateRejection = writeGate.submitIfWritable(() -> {
                var currentFailure = failure.get();
                if (currentFailure != null) {
                    publisherRejection.set(currentFailure);
                    return;
                }
                addInFlight(result);
                producer.send(
                    producerRecord,
                    (metadata, exception) ->
                        completeSend(result, metadata, exception, acknowledgedAction)
                );
            });
        } catch (Exception t) {
            removeInFlight(result);
            failPublisher(t);
            result.completeExceptionally(t);
            return result;
        }
        var rejection = gateRejection == null ? publisherRejection.get() : gateRejection;
        if (rejection != null) {
            failPublisher(rejection);
            result.completeExceptionally(rejection);
        }
        return result;
    }

    private void completeSend(
        CompletableFuture<RecordMetadata> result,
        RecordMetadata metadata,
        Exception exception,
        Runnable acknowledgedAction
    ) {
        if (exception != null) {
            removeInFlight(result);
            failPublisher(exception);
            result.completeExceptionally(exception);
            return;
        }
        try {
            executor.execute(() -> {
                if (result.isDone()) {
                    removeInFlight(result);
                    return;
                }
                try {
                    acknowledgedAction.run();
                    removeInFlight(result);
                    result.complete(metadata);
                } catch (Exception t) {
                    removeInFlight(result);
                    failPublisher(t);
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            removeInFlight(result);
            result.completeExceptionally(e);
        }
    }

    private void addInFlight(CompletableFuture<RecordMetadata> result) {
        synchronized (inFlightLock) {
            inFlightSends.add(result);
        }
    }

    private void removeInFlight(CompletableFuture<RecordMetadata> result) {
        synchronized (inFlightLock) {
            inFlightSends.remove(result);
        }
    }

    private void executeOnPublisher(Runnable action, CompletableFuture<?> result) {
        var currentFailure = failure.get();
        if (currentFailure == null) {
            currentFailure = writeGate.failureIfNotWritable();
        }
        if (currentFailure != null) {
            failPublisher(currentFailure);
            result.completeExceptionally(currentFailure);
            return;
        }
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("Capture Kafka publisher is closed"));
            return;
        }
        try {
            executeInternal(() -> {
                var taskFailure = failure.get();
                if (taskFailure == null) {
                    taskFailure = writeGate.failureIfNotWritable();
                }
                if (taskFailure != null) {
                    failPublisher(taskFailure);
                    result.completeExceptionally(taskFailure);
                } else {
                    try {
                        action.run();
                    } catch (Exception t) {
                        failPublisher(t);
                        result.completeExceptionally(t);
                    }
                }
            }, result);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
    }

    private void executeInternal(Runnable action, CompletableFuture<?> result) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
    }

    private void publishScheduledSnapshot() {
        publishLivenessSnapshotNow().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                log.atError()
                    .setCause(throwable)
                    .setMessage("Authoritative proxy liveness publishing has stopped")
                    .log();
            }
        });
    }

    private void failPublisher(Throwable throwable) {
        if (!failure.compareAndSet(null, throwable)) {
            return;
        }
        writeGate.trip(throwable);
        List<CompletableFuture<RecordMetadata>> pending;
        synchronized (inFlightLock) {
            pending = List.copyOf(inFlightSends);
            inFlightSends.clear();
        }
        pending.forEach(send -> send.completeExceptionally(throwable));
        scheduledSnapshots.cancel(false);
        log.atError()
            .setCause(throwable)
            .setMessage("Capture Kafka publisher failed closed; no more liveness declarations will be sent")
            .log();
    }

    void failClosed(Throwable throwable) {
        failPublisher(Objects.requireNonNull(throwable));
    }

    private static RecordHeaders recordHeaders(String recordType) {
        return new RecordHeaders(List.of(new RecordHeader(
            RECORD_TYPE_HEADER,
            recordType.getBytes(StandardCharsets.UTF_8)
        )));
    }

    public static boolean isRecordType(Iterable<org.apache.kafka.common.header.Header> headers, String expected) {
        for (var header : headers) {
            if (RECORD_TYPE_HEADER.equals(header.key())
                && expected.equals(new String(header.value(), StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduledSnapshots.cancel(false);
        try {
            var flush = new CompletableFuture<Void>();
            try {
                executor.execute(() -> {
                    try {
                        producer.flush();
                        flush.complete(null);
                    } catch (Exception t) {
                        flush.completeExceptionally(t);
                    }
                });
                flush.get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.atWarn().setCause(e).setMessage("Unable to flush capture Kafka publisher cleanly").log();
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                producer.close(CLOSE_TIMEOUT);
            }
        }
    }
}
