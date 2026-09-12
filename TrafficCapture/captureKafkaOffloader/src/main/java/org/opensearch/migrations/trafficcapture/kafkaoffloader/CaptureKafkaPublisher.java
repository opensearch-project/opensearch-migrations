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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.LivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.NoMoreWrites;

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
public class CaptureKafkaPublisher implements CaptureAssignmentPublisher, AutoCloseable {
    public static final String RECORD_TYPE_HEADER = CaptureRecordTypes.RECORD_TYPE_HEADER;
    public static final String WRITER_NODE_ID_HEADER = CaptureRecordTypes.WRITER_NODE_ID_HEADER;
    public static final String TRAFFIC_RECORD_TYPE = CaptureRecordTypes.TRAFFIC_RECORD_TYPE;
    public static final String LIVENESS_RECORD_TYPE = CaptureRecordTypes.LIVENESS_RECORD_TYPE;
    public static final String NO_MORE_WRITES_RECORD_TYPE =
        CaptureRecordTypes.NO_MORE_WRITES_RECORD_TYPE;

    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private record WriterPartitionKey(String writerNodeId, int partition) {}

    private record ManifestRequest(
        CaptureRoutingState.PendingAssignment pendingAssignment,
        CaptureRoutingState.PreparedManifest retirementManifest,
        boolean orderlyRetirement,
        CompletableFuture<?> result
    ) {}

    private record ManifestPublication(
        CaptureRoutingState.PreparedManifest manifest,
        List<CompletableFuture<RecordMetadata>> chunkAcknowledgements
    ) {}

    private record ManifestDeadline(long generation, ScheduledFuture<?> task) {}

    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final CaptureRoutingState routingState;
    private final CaptureKafkaWriteGate writeGate;
    private final Consumer<Throwable> unstableProcessFailureCallback;
    private final int payloadSizeLimit;
    private final Duration manifestExpirationInterval;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<WriterPartitionKey, Long> lastControlTimestamp = new HashMap<>();
    private final Map<WriterPartitionKey, CompletableFuture<Void>> writerRetirementResults =
        new HashMap<>();
    private final Object manifestDeadlineLock = new Object();
    private final Map<WriterPartitionKey, ManifestDeadline> manifestDeadlines = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean unstableProcessFailureReported = new AtomicBoolean();
    private final Object inFlightLock = new Object();
    private final Set<CompletableFuture<RecordMetadata>> inFlightSends =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<ManifestRequest> manifestRequests = new ArrayDeque<>();
    private final AtomicBoolean scheduledManifestPending = new AtomicBoolean();
    private boolean manifestPublicationActive;
    private long nextManifestDeadlineGeneration;
    private final ScheduledFuture<?> scheduledSnapshots;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            producer,
            topic,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            KafkaCaptureFactory.DEFAULT_MANIFEST_EXPIRATION_INTERVAL,
            Clock.systemUTC(),
            CaptureKafkaWriteGate.unrestricted(),
            unstableProcessFailureCallback
        );
    }

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Duration manifestExpirationInterval,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            producer,
            topic,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            manifestExpirationInterval,
            Clock.systemUTC(),
            CaptureKafkaWriteGate.unrestricted(),
            unstableProcessFailureCallback
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            producer,
            topic,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            KafkaCaptureFactory.DEFAULT_MANIFEST_EXPIRATION_INTERVAL,
            clock,
            CaptureKafkaWriteGate.unrestricted(),
            unstableProcessFailureCallback
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Clock clock,
        CaptureKafkaWriteGate writeGate,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            producer,
            topic,
            routingState,
            maximumKafkaMessageSize,
            snapshotInterval,
            KafkaCaptureFactory.DEFAULT_MANIFEST_EXPIRATION_INTERVAL,
            clock,
            writeGate,
            unstableProcessFailureCallback
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration snapshotInterval,
        Duration manifestExpirationInterval,
        Clock clock,
        CaptureKafkaWriteGate writeGate,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.producer = Objects.requireNonNull(producer);
        this.topic = Objects.requireNonNull(topic);
        this.routingState = Objects.requireNonNull(routingState);
        this.clock = Objects.requireNonNull(clock);
        this.writeGate = Objects.requireNonNull(writeGate);
        this.unstableProcessFailureCallback = Objects.requireNonNull(unstableProcessFailureCallback);
        if (maximumKafkaMessageSize <= KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("maximumKafkaMessageSize is too small for Kafka record overhead");
        }
        payloadSizeLimit = maximumKafkaMessageSize - KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES;
        if (snapshotInterval.isZero() || snapshotInterval.isNegative()) {
            throw new IllegalArgumentException("snapshotInterval must be positive");
        }
        this.manifestExpirationInterval = Objects.requireNonNull(manifestExpirationInterval);
        if (manifestExpirationInterval.isZero() || manifestExpirationInterval.isNegative()) {
            throw new IllegalArgumentException("manifestExpirationInterval must be positive");
        }
        if (snapshotInterval.compareTo(manifestExpirationInterval) >= 0) {
            throw new IllegalArgumentException(
                "snapshotInterval must be lower than manifestExpirationInterval"
            );
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

    @Override
    public CompletableFuture<String> installAssignment(Collection<Integer> partitions) {
        var result = new CompletableFuture<String>();
        executeOnPublisher(() -> {
            var pendingAssignment = routingState.prepareAssignment(partitions);
            manifestRequests.addLast(new ManifestRequest(pendingAssignment, null, false, result));
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
        return enqueueTrafficSend(
            route,
            producerRecord,
            finalRecord,
            finalRecord
                ? () -> {
                    routingState.removeAfterTerminalAcknowledgement(route);
                    enqueueDrainedWriterRetirements();
                }
                : () -> {}
        );
    }

    public CompletableFuture<Void> publishLivenessSnapshotNow() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            manifestRequests.addLast(new ManifestRequest(null, null, false, result));
            startNextManifestRequest();
        }, result);
        return result;
    }

    void validateCriticalMutationTrafficAcknowledgement(
        CaptureRoutingState.ConnectionRoute route,
        RecordMetadata acknowledgement
    ) {
        Objects.requireNonNull(route);
        if (acknowledgement == null
            || acknowledgement.partition() != route.partition()
            || !acknowledgement.hasTimestamp()) {
            throw new IllegalStateException(
                "Kafka returned invalid acknowledgement metadata for Critical Mutation Traffic"
            );
        }
        try {
            routingState.validateCriticalMutationTrafficAcknowledgement(
                route,
                acknowledgement.timestamp(),
                manifestExpirationInterval
            );
        } catch (CorruptedCaptureStateException e) {
            failUnstableProcess(e);
            throw e;
        }
    }

    CompletableFuture<Void> retireAllWriters() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            manifestRequests.addLast(new ManifestRequest(null, null, true, result));
            startNextManifestRequest();
        }, result);
        return result;
    }

    void abandonUnpublishedConnection(CaptureRoutingState.ConnectionRoute route) {
        routingState.abandonUnpublishedConnection(route);
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
            if (request.orderlyRetirement()) {
                manifests = List.of();
            } else if (request.retirementManifest() != null) {
                manifests = List.of(request.retirementManifest());
            } else if (request.pendingAssignment() != null) {
                manifests = routingState.prepareInitialManifests(request.pendingAssignment());
            } else {
                manifests = routingState.preparePeriodicManifests();
            }
        } catch (Throwable t) {
            finishManifestRequest(request, List.of(), t);
            return;
        }

        var publications = new ArrayList<ManifestPublication>();
        try {
            for (var manifest : manifests) {
                ensureManifestDeadline(manifest);
                var emittedAtMillis = allocateControlTimestamp(
                    manifest.writerNodeId(),
                    manifest.partition()
                );
                var chunkAcknowledgements = new ArrayList<CompletableFuture<RecordMetadata>>();
                for (var chunk : buildSnapshotChunks(manifest, emittedAtMillis)) {
                    var key = manifest.writerNodeId() + ":liveness:" + manifest.partition();
                    var producerRecord = new ProducerRecord<>(
                        topic,
                        manifest.partition(),
                        emittedAtMillis,
                        key,
                        chunk.toByteArray(),
                        recordHeaders(LIVENESS_RECORD_TYPE)
                    );
                    chunkAcknowledgements.add(sendFromPublisherThread(producerRecord, () -> {}));
                }
                publications.add(new ManifestPublication(manifest, List.copyOf(chunkAcknowledgements)));
            }
        } catch (Throwable t) {
            finishManifestRequest(request, List.of(), t);
            return;
        }

        CompletableFuture.allOf(
            publications.stream()
                .flatMap(publication -> publication.chunkAcknowledgements().stream())
                .toArray(CompletableFuture[]::new)
        )
            .whenComplete((ignored, throwable) ->
                finishManifestRequestOnPublisherThread(request, publications, throwable)
            );
    }

    private void finishManifestRequestOnPublisherThread(
        ManifestRequest request,
        List<ManifestPublication> publications,
        Throwable failure
    ) {
        try {
            executor.execute(() -> finishManifestRequest(request, publications, failure));
        } catch (RejectedExecutionException e) {
            request.result().completeExceptionally(failure == null ? e : failure);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void finishManifestRequest(
        ManifestRequest request,
        List<ManifestPublication> publications,
        Throwable requestFailure
    ) {
        if (requestFailure == null) {
            try {
                for (var publication : publications) {
                    var manifestLogAppendTime = publication.chunkAcknowledgements()
                        .stream()
                        .map(CompletableFuture::join)
                        .mapToLong(CaptureKafkaPublisher::requireBrokerTimestamp)
                        .max()
                        .orElseThrow(() -> new IllegalStateException(
                            "A complete manifest must contain at least one Kafka record"
                        ));
                    routingState.acceptManifestLogAppendTime(
                        publication.manifest(),
                        manifestLogAppendTime,
                        manifestExpirationInterval
                    );
                    renewManifestDeadline(publication.manifest());
                }
                if (request.orderlyRetirement()) {
                    beginOrderlyWriterRetirement(request);
                    return;
                } else if (request.retirementManifest() != null) {
                    publishNoMoreWrites(request);
                    return;
                } else if (request.pendingAssignment() != null) {
                    routingState.activateAssignment(request.pendingAssignment());
                    ((CompletableFuture<String>) request.result()).complete(
                        request.pendingAssignment().writerNodeId()
                    );
                    enqueueDrainedWriterRetirements();
                } else {
                    ((CompletableFuture<Void>) request.result()).complete(null);
                }
            } catch (Throwable t) {
                requestFailure = t;
            }
        }
        if (requestFailure != null) {
            request.result().completeExceptionally(requestFailure);
            failForThrowable(requestFailure);
        }
        manifestPublicationActive = false;
        startNextManifestRequest();
    }

    private static long requireBrokerTimestamp(RecordMetadata metadata) {
        if (metadata == null || !metadata.hasTimestamp() || metadata.timestamp() <= 0) {
            throw new IllegalStateException(
                "Kafka did not report a positive LogAppendTime for an acknowledged manifest record"
            );
        }
        return metadata.timestamp();
    }

    private void beginOrderlyWriterRetirement(ManifestRequest request) {
        scheduledSnapshots.cancel(false);
        routingState.beginOrderlyRetirement();
        enqueueDrainedWriterRetirements();
        var retirements = List.copyOf(writerRetirementResults.values());
        manifestPublicationActive = false;
        startNextManifestRequest();
        CompletableFuture.allOf(retirements.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) ->
                finishOrderlyWriterRetirement(request, failure)
            );
    }

    @SuppressWarnings("unchecked")
    private void finishOrderlyWriterRetirement(
        ManifestRequest request,
        Throwable requestFailure
    ) {
        if (requestFailure != null) {
            request.result().completeExceptionally(requestFailure);
            return;
        }
        if (!routingState.allWriterPartitionsRetired()) {
            var failure = new IllegalStateException(
                "Orderly proxy retirement completed without retiring every writer partition"
            );
            request.result().completeExceptionally(failure);
            failUnstableProcess(failure);
            return;
        }
        ((CompletableFuture<Void>) request.result()).complete(null);
    }

    private void publishNoMoreWrites(ManifestRequest request) {
        var manifest = request.retirementManifest();
        var producerRecord = new ProducerRecord<>(
            topic,
            manifest.partition(),
            null,
            manifest.writerNodeId() + ":no-more-writes:" + manifest.partition(),
            NoMoreWrites.newBuilder().setPartition(manifest.partition()).build().toByteArray(),
            writerRecordHeaders(NO_MORE_WRITES_RECORD_TYPE, manifest.writerNodeId())
        );
        sendFromPublisherThread(
            producerRecord,
            () -> {
                routingState.completeWriterRetirement(manifest);
                cancelManifestDeadline(manifest);
            }
        ).whenComplete((ignored, throwable) ->
            finishWriterRetirementOnPublisherThread(request, throwable)
        );
    }

    private void finishWriterRetirementOnPublisherThread(
        ManifestRequest request,
        Throwable failure
    ) {
        try {
            executor.execute(() -> finishWriterRetirement(request, failure));
        } catch (RejectedExecutionException e) {
            request.result().completeExceptionally(failure == null ? e : failure);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void finishWriterRetirement(ManifestRequest request, Throwable requestFailure) {
        if (requestFailure == null) {
            ((CompletableFuture<Void>) request.result()).complete(null);
        } else {
            request.result().completeExceptionally(requestFailure);
            failForThrowable(requestFailure);
        }
        manifestPublicationActive = false;
        startNextManifestRequest();
    }

    private void enqueueDrainedWriterRetirements() {
        for (var manifest : routingState.prepareDrainedWriterRetirements()) {
            var result = new CompletableFuture<Void>();
            var key = new WriterPartitionKey(manifest.writerNodeId(), manifest.partition());
            if (writerRetirementResults.putIfAbsent(key, result) != null) {
                throw new CorruptedCaptureStateException(
                    "Writer retirement was already queued for "
                        + manifest.writerNodeId()
                        + "/"
                        + manifest.partition()
                );
            }
            result.whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    log.atInfo()
                        .setMessage("Retired proxy writer {} for partition {}")
                        .addArgument(manifest.writerNodeId())
                        .addArgument(manifest.partition())
                        .log();
                }
            });
            manifestRequests.addLast(new ManifestRequest(null, manifest, false, result));
        }
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

    private void ensureManifestDeadline(CaptureRoutingState.PreparedManifest manifest) {
        var key = new WriterPartitionKey(manifest.writerNodeId(), manifest.partition());
        synchronized (manifestDeadlineLock) {
            if (!manifestDeadlines.containsKey(key)) {
                scheduleManifestDeadline(key);
            }
        }
    }

    private void renewManifestDeadline(CaptureRoutingState.PreparedManifest manifest) {
        var key = new WriterPartitionKey(manifest.writerNodeId(), manifest.partition());
        synchronized (manifestDeadlineLock) {
            var previous = manifestDeadlines.remove(key);
            if (previous != null) {
                previous.task().cancel(false);
            }
            scheduleManifestDeadline(key);
        }
    }

    private void scheduleManifestDeadline(WriterPartitionKey key) {
        if (closed.get() || failure.get() != null) {
            return;
        }
        var generation = Math.incrementExact(nextManifestDeadlineGeneration);
        var task = executor.schedule(
            () -> expireManifestDeadline(key, generation),
            manifestExpirationInterval.toNanos(),
            TimeUnit.NANOSECONDS
        );
        manifestDeadlines.put(key, new ManifestDeadline(generation, task));
    }

    private void expireManifestDeadline(WriterPartitionKey key, long generation) {
        synchronized (manifestDeadlineLock) {
            var current = manifestDeadlines.get(key);
            if (current == null || current.generation() != generation) {
                return;
            }
            manifestDeadlines.remove(key);
        }
        if (closed.get() || failure.get() != null) {
            return;
        }
        failPublisher(new TimeoutException(
            "Manifest acknowledgement deadline expired for "
                + key.writerNodeId()
                + "/"
                + key.partition()
        ));
    }

    private void cancelManifestDeadline(CaptureRoutingState.PreparedManifest manifest) {
        var key = new WriterPartitionKey(manifest.writerNodeId(), manifest.partition());
        synchronized (manifestDeadlineLock) {
            var deadline = manifestDeadlines.remove(key);
            if (deadline != null) {
                deadline.task().cancel(false);
            }
        }
    }

    private void cancelAllManifestDeadlines() {
        synchronized (manifestDeadlineLock) {
            manifestDeadlines.values().forEach(deadline -> deadline.task().cancel(false));
            manifestDeadlines.clear();
        }
    }

    private CompletableFuture<RecordMetadata> enqueueTrafficSend(
        CaptureRoutingState.ConnectionRoute route,
        ProducerRecord<String, byte[]> producerRecord,
        boolean finalRecord,
        Runnable acknowledgedAction
    ) {
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() -> {
            try {
                routingState.acceptTrafficSubmission(route, finalRecord);
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                failUnstableProcess(e);
                return;
            }
            sendFromPublisherThread(producerRecord, acknowledgedAction)
                .whenComplete((metadata, throwable) -> completeFrom(metadata, throwable, result));
        }, result);
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
        } catch (Throwable t) {
            removeInFlight(result);
            failForThrowable(t);
            result.completeExceptionally(t);
            return result;
        }
        var rejection = gateRejection == null ? publisherRejection.get() : gateRejection;
        if (rejection != null) {
            failForThrowable(rejection);
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
                } catch (Throwable t) {
                    removeInFlight(result);
                    failForThrowable(t);
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            removeInFlight(result);
            result.completeExceptionally(e);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
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
            failForThrowable(currentFailure);
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
                    failForThrowable(taskFailure);
                    result.completeExceptionally(taskFailure);
                } else {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        failForThrowable(t);
                        result.completeExceptionally(t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
    }

    private void publishScheduledSnapshot() {
        if (!scheduledManifestPending.compareAndSet(false, true)) {
            return;
        }
        try {
            publishLivenessSnapshotNow().whenComplete((ignored, throwable) -> {
                scheduledManifestPending.set(false);
                if (throwable != null) {
                    log.atError()
                        .setCause(throwable)
                        .setMessage("Authoritative proxy liveness publishing has stopped")
                        .log();
                }
            });
        } catch (Throwable t) {
            scheduledManifestPending.set(false);
            failForThrowable(t);
        }
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
        cancelAllManifestDeadlines();
        try {
            executor.execute(this::startNextManifestRequest);
        } catch (RejectedExecutionException e) {
            log.atWarn()
                .setCause(e)
                .setMessage("Publisher executor stopped before queued manifest requests were failed")
                .log();
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
        log.atError()
            .setCause(throwable)
            .setMessage(
                "Capture Kafka publisher stopped permanently after failure; "
                    + "no more records will be submitted"
            )
            .log();
    }

    @Override
    public void stopAfterFailure(Throwable throwable) {
        failPublisher(Objects.requireNonNull(throwable));
    }

    private void failForThrowable(Throwable throwable) {
        if (throwable instanceof Error || throwable instanceof CorruptedCaptureStateException) {
            failUnstableProcess(throwable);
        } else {
            failPublisher(throwable);
        }
    }

    private void failUnstableProcess(Throwable throwable) {
        if (unstableProcessFailureReported.compareAndSet(false, true)) {
            unstableProcessFailureCallback.accept(throwable);
        }
        failPublisher(throwable);
    }

    private static RecordHeaders recordHeaders(String recordType) {
        return new RecordHeaders(List.of(new RecordHeader(
            RECORD_TYPE_HEADER,
            recordType.getBytes(StandardCharsets.UTF_8)
        )));
    }

    private static RecordHeaders writerRecordHeaders(String recordType, String writerNodeId) {
        return new RecordHeaders(List.of(
            new RecordHeader(RECORD_TYPE_HEADER, recordType.getBytes(StandardCharsets.UTF_8)),
            new RecordHeader(
                WRITER_NODE_ID_HEADER,
                writerNodeId.getBytes(StandardCharsets.UTF_8)
            )
        ));
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
        cancelAllManifestDeadlines();
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
