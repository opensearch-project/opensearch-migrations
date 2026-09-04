package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;

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

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final String nodeId;
    @Getter
    private final PartitionRoutingPlan routingPlan;
    @Getter
    private final ProxyLivenessRegistry livenessRegistry;
    private final int payloadSizeLimit;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<Integer, Long> nextSnapshotSequence = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> scheduledSnapshots;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        PartitionRoutingPlan routingPlan,
        ProxyLivenessRegistry livenessRegistry,
        int maximumKafkaMessageSize,
        Duration snapshotInterval
    ) {
        this(
            producer,
            topic,
            nodeId,
            routingPlan,
            livenessRegistry,
            maximumKafkaMessageSize,
            snapshotInterval,
            Clock.systemUTC()
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        PartitionRoutingPlan routingPlan,
        ProxyLivenessRegistry livenessRegistry,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock
    ) {
        this.producer = Objects.requireNonNull(producer);
        this.topic = Objects.requireNonNull(topic);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.routingPlan = Objects.requireNonNull(routingPlan);
        this.livenessRegistry = Objects.requireNonNull(livenessRegistry);
        this.clock = Objects.requireNonNull(clock);
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
    }

    public CompletableFuture<RecordMetadata> publishTraffic(
        String connectionId,
        int partition,
        byte[] payload,
        boolean finalRecord
    ) {
        if (routingPlan.partitionFor(connectionId) != partition) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    "Connection " + connectionId + " does not route to partition " + partition
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
            finalRecord ? () -> livenessRegistry.remove(connectionId, partition) : () -> {}
        );
    }

    public CompletableFuture<Void> publishLivenessSnapshotNow() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            var sends = new ArrayList<CompletableFuture<RecordMetadata>>();
            for (var partition : routingPlan.getSelectedPartitions()) {
                var sequence = nextSnapshotSequence.merge(partition, 1L, Long::sum) - 1;
                var chunks = buildSnapshotChunks(
                    partition,
                    sequence,
                    livenessRegistry.snapshot(partition)
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

    List<ProxyLivenessSnapshotChunk> buildSnapshotChunks(
        int partition,
        long sequence,
        List<String> openConnections
    ) {
        var chunkConnections = new ArrayList<List<ByteString>>();
        var current = new ArrayList<ByteString>();
        for (var connection : openConnections) {
            var encoded = ByteString.copyFromUtf8(connection);
            var candidate = new ArrayList<>(current);
            candidate.add(encoded);
            if (estimatedChunkSize(partition, sequence, candidate) <= payloadSizeLimit) {
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
            var chunk = baseSnapshotChunk(partition, sequence)
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

    private int estimatedChunkSize(int partition, long sequence, List<ByteString> connections) {
        return baseSnapshotChunk(partition, sequence)
            .setChunkIndex(Integer.MAX_VALUE)
            .setChunkCount(Integer.MAX_VALUE)
            .addAllOpenConnections(connections)
            .build()
            .getSerializedSize();
    }

    private ProxyLivenessSnapshotChunk.Builder baseSnapshotChunk(int partition, long sequence) {
        return ProxyLivenessSnapshotChunk.newBuilder()
            .setNodeId(nodeId)
            .setPartition(partition)
            .setRoutingPlanId(routingPlan.getRoutingPlanId())
            .setSnapshotSequence(sequence)
            .setEmittedAtMillis(clock.millis());
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
        var currentFailure = failure.get();
        if (currentFailure != null) {
            result.completeExceptionally(currentFailure);
            return result;
        }
        try {
            producer.send(producerRecord, (metadata, exception) -> {
                if (exception != null) {
                    failPublisher(exception);
                    result.completeExceptionally(exception);
                    return;
                }
                executeInternal(() -> {
                    try {
                        acknowledgedAction.run();
                        result.complete(metadata);
                    } catch (Exception t) {
                        failPublisher(t);
                        result.completeExceptionally(t);
                    }
                }, result);
            });
        } catch (Exception t) {
            failPublisher(t);
            result.completeExceptionally(t);
        }
        return result;
    }

    private void executeOnPublisher(Runnable action, CompletableFuture<?> result) {
        var currentFailure = failure.get();
        if (currentFailure != null) {
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
                if (taskFailure != null) {
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
        if (failure.compareAndSet(null, throwable)) {
            scheduledSnapshots.cancel(false);
            log.atError()
                .setCause(throwable)
                .setMessage("Capture Kafka publisher failed closed; no more liveness declarations will be sent")
                .log();
        }
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
