package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import org.opensearch.migrations.trafficcapture.protos.LivenessSnapshotChunk;

import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Owns the ordered Kafka submission lane for traffic and exact connection manifests.
 */
@Slf4j
public class CaptureKafkaPublisher implements AutoCloseable {
    public static final String RECORD_TYPE_HEADER = CaptureRecordTypes.RECORD_TYPE_HEADER;
    public static final String TRAFFIC_RECORD_TYPE = CaptureRecordTypes.TRAFFIC_RECORD_TYPE;
    public static final String LIVENESS_RECORD_TYPE = CaptureRecordTypes.LIVENESS_RECORD_TYPE;

    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private record WriterPartitionKey(String writerNodeId, int partition) {}

    private record ManifestRequest(
        CaptureRoutingState.PendingAssignment pendingAssignment,
        CompletableFuture<?> result
    ) {}

    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final CaptureRoutingState routingState;
    private final CaptureKafkaWriteGate writeGate;
    private final int payloadSizeLimit;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<WriterPartitionKey, Long> lastControlTimestamp = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object inFlightLock = new Object();
    private final Set<CompletableFuture<RecordMetadata>> inFlightSends =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<ManifestRequest> manifestRequests = new ArrayDeque<>();
    private final AtomicBoolean scheduledManifestPending = new AtomicBoolean();
    private boolean manifestPublicationActive;
    private final ScheduledFuture<?> scheduledSnapshots;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval
    ) {
        this(
            producer,
            topic,
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
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock
    ) {
        this(
            producer,
            topic,
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
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock,
        CaptureKafkaWriteGate writeGate
    ) {
        this.producer = Objects.requireNonNull(producer);
        this.topic = Objects.requireNonNull(topic);
        this.routingState = Objects.requireNonNull(routingState);
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

    CompletableFuture<String> installAssignment(Collection<Integer> partitions) {
        var result = new CompletableFuture<String>();
        executeOnPublisher(() -> {
            var pendingAssignment = routingState.prepareAssignment(partitions);
            manifestRequests.addLast(new ManifestRequest(pendingAssignment, result));
            startNextManifestRequest();
        }, result);
        return result;
    }

    public CompletableFuture<RecordMetadata> publishTraffic(
        CaptureRoutingState.ConnectionRoute route,
        byte[] payload,
        boolean finalRecord
    ) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(payload);
        var producerRecord = new ProducerRecord<>(
            topic,
            route.partition(),
            null,
            route.connectionId(),
            payload.clone(),
            recordHeaders(TRAFFIC_RECORD_TYPE)
        );
        return enqueueSend(
            producerRecord,
            finalRecord ? () -> routingState.remove(route) : () -> {}
        );
    }

    public CompletableFuture<Void> publishLivenessSnapshotNow() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            manifestRequests.addLast(new ManifestRequest(null, result));
            startNextManifestRequest();
        }, result);
        return result;
    }

    void removeConnectionRegistration(CaptureRoutingState.ConnectionRoute route) {
        routingState.remove(route);
    }

    private void startNextManifestRequest() {
        if (manifestPublicationActive) {
            return;
        }
        var terminalFailure = failure.get();
        if (terminalFailure == null && closed.get()) {
            terminalFailure = new IllegalStateException("Capture Kafka publisher is closed");
        }
        if (terminalFailure != null) {
            while (!manifestRequests.isEmpty()) {
                manifestRequests.removeFirst().result().completeExceptionally(terminalFailure);
            }
            return;
        }
        var request = manifestRequests.pollFirst();
        if (request == null) {
            return;
        }
        manifestPublicationActive = true;
        final List<CaptureRoutingState.PreparedManifest> manifests;
        try {
            manifests = request.pendingAssignment() == null
                ? routingState.preparePeriodicManifests()
                : routingState.prepareInitialManifests(request.pendingAssignment());
        } catch (Throwable t) {
            finishManifestRequest(request, t);
            return;
        }

        var sends = new ArrayList<CompletableFuture<RecordMetadata>>();
        try {
            for (var manifest : manifests) {
                var emittedAtMillis = allocateControlTimestamp(
                    manifest.writerNodeId(),
                    manifest.partition()
                );
                for (var chunk : buildSnapshotChunks(manifest, emittedAtMillis)) {
                    var key = manifest.writerNodeId() + ":liveness:" + manifest.partition();
                    var producerRecord = new ProducerRecord<>(
                        topic,
                        manifest.partition(),
                        null,
                        key,
                        chunk.toByteArray(),
                        recordHeaders(LIVENESS_RECORD_TYPE)
                    );
                    sends.add(sendFromPublisherThread(producerRecord, () -> {}));
                }
            }
        } catch (Throwable t) {
            finishManifestRequest(request, t);
            return;
        }

        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, throwable) ->
                finishManifestRequestOnPublisherThread(request, throwable)
            );
    }

    private void finishManifestRequestOnPublisherThread(
        ManifestRequest request,
        Throwable failure
    ) {
        try {
            executor.execute(() -> finishManifestRequest(request, failure));
        } catch (RejectedExecutionException e) {
            request.result().completeExceptionally(failure == null ? e : failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void finishManifestRequest(ManifestRequest request, Throwable requestFailure) {
        if (requestFailure == null) {
            try {
                if (request.pendingAssignment() != null) {
                    routingState.activateAssignment(request.pendingAssignment());
                    ((CompletableFuture<String>) request.result()).complete(
                        request.pendingAssignment().writerNodeId()
                    );
                } else {
                    ((CompletableFuture<Void>) request.result()).complete(null);
                }
            } catch (Throwable t) {
                requestFailure = t;
            }
        }
        if (requestFailure != null) {
            request.result().completeExceptionally(requestFailure);
            failPublisher(requestFailure);
        }
        manifestPublicationActive = false;
        startNextManifestRequest();
    }

    List<LivenessSnapshotChunk> buildSnapshotChunks(
        CaptureRoutingState.PreparedManifest manifest,
        long emittedAtMillis
    ) {
        var chunkConnections = new ArrayList<List<ByteString>>();
        var current = new ArrayList<ByteString>();
        for (var connection : manifest.connectionIds()) {
            var encoded = ByteString.copyFromUtf8(connection);
            var candidate = new ArrayList<>(current);
            candidate.add(encoded);
            if (estimatedChunkSize(manifest, emittedAtMillis, candidate) <= payloadSizeLimit) {
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
        var chunks = new ArrayList<LivenessSnapshotChunk>(chunkCount);
        for (int i = 0; i < chunkCount; ++i) {
            var chunk = baseSnapshotChunk(manifest, emittedAtMillis)
                .setChunkIndex(i)
                .setChunkCount(chunkCount)
                .addAllConnectionIds(chunkConnections.get(i))
                .build();
            if (chunk.getSerializedSize() > payloadSizeLimit) {
                throw new IllegalStateException("Liveness snapshot chunk exceeds Kafka payload limit");
            }
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private int estimatedChunkSize(
        CaptureRoutingState.PreparedManifest manifest,
        long emittedAtMillis,
        List<ByteString> connections
    ) {
        return baseSnapshotChunk(manifest, emittedAtMillis)
            .setChunkIndex(Integer.MAX_VALUE)
            .setChunkCount(Integer.MAX_VALUE)
            .addAllConnectionIds(connections)
            .build()
            .getSerializedSize();
    }

    private LivenessSnapshotChunk.Builder baseSnapshotChunk(
        CaptureRoutingState.PreparedManifest manifest,
        long emittedAtMillis
    ) {
        return LivenessSnapshotChunk.newBuilder()
            .setWriterNodeId(manifest.writerNodeId())
            .setPartition(manifest.partition())
            .setManifestCycle(manifest.manifestCycle())
            .setEmittedAtMillis(emittedAtMillis);
    }

    private long allocateControlTimestamp(String writerNodeId, int partition) {
        var key = new WriterPartitionKey(writerNodeId, partition);
        var observed = clock.millis();
        var previous = lastControlTimestamp.get(key);
        var allocated = previous == null || observed > previous ? observed : Math.incrementExact(previous);
        lastControlTimestamp.put(key, allocated);
        return allocated;
    }

    private CompletableFuture<RecordMetadata> enqueueSend(
        ProducerRecord<String, byte[]> producerRecord,
        Runnable acknowledgedAction
    ) {
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() ->
            sendFromPublisherThread(producerRecord, acknowledgedAction)
                .whenComplete((metadata, throwable) -> completeFrom(metadata, throwable, result)),
            result
        );
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
            executor.execute(() -> {
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
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
    }

    private void publishScheduledSnapshot() {
        if (!scheduledManifestPending.compareAndSet(false, true)) {
            return;
        }
        publishLivenessSnapshotNow().whenComplete((ignored, throwable) -> {
            scheduledManifestPending.set(false);
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
        try {
            executor.execute(this::startNextManifestRequest);
        } catch (RejectedExecutionException e) {
            log.atWarn()
                .setCause(e)
                .setMessage("Publisher executor stopped before queued manifest requests were failed")
                .log();
        }
        log.atError()
            .setCause(throwable)
            .setMessage("Capture Kafka publisher failed closed; no more manifests will be sent")
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

    public static boolean isRecordType(
        Iterable<org.apache.kafka.common.header.Header> headers,
        String expected
    ) {
        for (var header : headers) {
            if (RECORD_TYPE_HEADER.equals(header.key())
                && expected.equals(new String(header.value(), StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduledSnapshots.cancel(false);
        routingState.beginShutdown();
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
