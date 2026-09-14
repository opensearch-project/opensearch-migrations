package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
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

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriterPartitionHeartbeat;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Owns the single Kafka producer lane, assignment heartbeats, connection traffic submissions,
 * acknowledgement callbacks, and local writer-partition retirement.
 */
@Slf4j
public class CaptureKafkaPublisher implements CaptureAssignmentPublisher, AutoCloseable {
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private enum PublisherLaneStatus {
        OPEN,
        RETIRING,
        RETIRED
    }

    private enum AcknowledgementDeadlineStatus {
        PENDING,
        ACKNOWLEDGED,
        EXPIRED,
        CANCELLED
    }

    private static final class AcknowledgementDeadline {
        private final AtomicReference<AcknowledgementDeadlineStatus> status =
            new AtomicReference<>(AcknowledgementDeadlineStatus.PENDING);
        private volatile ScheduledFuture<?> expirationTask;

        private boolean acknowledge() {
            if (!status.compareAndSet(
                AcknowledgementDeadlineStatus.PENDING,
                AcknowledgementDeadlineStatus.ACKNOWLEDGED
            )) {
                return false;
            }
            cancelExpirationTask();
            return true;
        }

        private boolean expire() {
            return status.compareAndSet(
                AcknowledgementDeadlineStatus.PENDING,
                AcknowledgementDeadlineStatus.EXPIRED
            );
        }

        private void cancel() {
            status.compareAndSet(
                AcknowledgementDeadlineStatus.PENDING,
                AcknowledgementDeadlineStatus.CANCELLED
            );
            cancelExpirationTask();
        }

        private void setExpirationTask(ScheduledFuture<?> expirationTask) {
            this.expirationTask = expirationTask;
            if (status.get() != AcknowledgementDeadlineStatus.PENDING) {
                expirationTask.cancel(false);
            }
        }

        private AcknowledgementDeadlineStatus status() {
            return status.get();
        }

        private void cancelExpirationTask() {
            var task = expirationTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class PublisherLane {
        private final CaptureRoutingState.WriterPartition writerPartition;
        private final CompletableFuture<Void> retirement = new CompletableFuture<>();
        private PublisherLaneStatus status = PublisherLaneStatus.OPEN;
        private ScheduledFuture<?> nextHeartbeat;
        private AcknowledgementDeadline acknowledgementDeadline;
        private boolean heartbeatInFlight;
        private int acceptedSends;

        private PublisherLane(CaptureRoutingState.WriterPartition writerPartition) {
            this.writerPartition = writerPartition;
        }
    }

    private record AssignmentInstallation(
        List<Integer> partitions,
        CompletableFuture<String> result
    ) {}

    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final CaptureRoutingState routingState;
    private final CaptureKafkaWriteGate writeGate;
    private final Consumer<Throwable> unstableProcessFailureCallback;
    private final int payloadSizeLimit;
    private final Duration heartbeatInterval;
    private final Duration heartbeatExpirationInterval;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor acknowledgementDeadlineExecutor;
    private final AtomicReference<Thread> publisherThread = new AtomicReference<>();
    private final Map<CaptureRoutingState.WriterPartition, PublisherLane> publisherLanes =
        new HashMap<>();
    private final ArrayDeque<AssignmentInstallation> assignmentInstallations = new ArrayDeque<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean unstableProcessFailureReported = new AtomicBoolean();
    private final Object inFlightLock = new Object();
    private final Set<CompletableFuture<RecordMetadata>> inFlightSends =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private CompletableFuture<Void> orderlyRetirement;
    private boolean assignmentInstallationInProgress;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            producer,
            topic,
            routingState,
            maximumKafkaMessageSize,
            heartbeatInterval,
            heartbeatExpirationInterval,
            Clock.systemUTC(),
            new CaptureKafkaWriteGate(),
            unstableProcessFailureCallback
        );
    }

    CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
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
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        this.heartbeatExpirationInterval = requirePositive(
            heartbeatExpirationInterval,
            "heartbeatExpirationInterval"
        );
        if (heartbeatInterval.compareTo(heartbeatExpirationInterval) >= 0) {
            throw new IllegalArgumentException(
                "heartbeatInterval must be lower than heartbeatExpirationInterval"
            );
        }
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-publisher");
            thread.setDaemon(true);
            publisherThread.compareAndSet(null, thread);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        acknowledgementDeadlineExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-heartbeat-deadline");
            thread.setDaemon(true);
            return thread;
        });
        acknowledgementDeadlineExecutor.setRemoveOnCancelPolicy(true);
        writeGate.addTerminalFailureListener(this::failPublisher);
    }

    @Override
    public CompletableFuture<String> installAssignment(Collection<Integer> partitions) {
        var result = new CompletableFuture<String>();
        var partitionSnapshot = List.copyOf(Objects.requireNonNull(partitions));
        executeOnPublisher(() -> {
            assignmentInstallations.addLast(new AssignmentInstallation(partitionSnapshot, result));
            startNextAssignmentInstallation();
        }, result);
        return result;
    }

    private void startNextAssignmentInstallation() {
        if (assignmentInstallationInProgress || assignmentInstallations.isEmpty()) {
            return;
        }
        assignmentInstallationInProgress = true;
        var installation = assignmentInstallations.removeFirst();
        installAssignmentOnPublisher(installation.partitions(), installation.result());
    }

    private void installAssignmentOnPublisher(
        Collection<Integer> partitions,
        CompletableFuture<String> result
    ) {
        var pendingAssignment = routingState.prepareAssignment(partitions);
        var initialHeartbeats = pendingAssignment.writerPartitions()
            .stream()
            .map(writerPartition -> {
                var lane = new PublisherLane(writerPartition);
                if (publisherLanes.putIfAbsent(writerPartition, lane) != null) {
                    throw new CorruptedCaptureStateException(
                        "Publisher lane already exists for " + writerPartition
                    );
                }
                return publishHeartbeat(lane);
            })
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(initialHeartbeats)
            .whenComplete((ignored, assignmentFailure) ->
                executeCompletionOnPublisher(
                    () -> finishAssignmentInstallation(
                        pendingAssignment,
                        result,
                        assignmentFailure
                    ),
                    result
                )
            );
    }

    private void finishAssignmentInstallation(
        CaptureRoutingState.PendingAssignment pendingAssignment,
        CompletableFuture<String> result,
        Throwable assignmentFailure
    ) {
        if (assignmentFailure != null) {
            var cause = unwrapCompletionFailure(assignmentFailure);
            result.completeExceptionally(cause);
            failForThrowable(cause);
            return;
        }
        try {
            routingState.activateAssignment(pendingAssignment);
            result.complete(pendingAssignment.writerNodeId());
            beginDrainedWriterRetirements();
            assignmentInstallationInProgress = false;
            startNextAssignmentInstallation();
        } catch (Throwable t) {
            result.completeExceptionally(t);
            failForThrowable(t);
        }
    }

    public CompletableFuture<RecordMetadata> publishTraffic(
        CaptureRoutingState.ConnectionRoute route,
        TrafficStream trafficStream,
        boolean finalRecord
    ) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(trafficStream);
        if (!route.writerNodeId().equals(trafficStream.getNodeId())
            || !route.connectionId().equals(trafficStream.getConnectionId())) {
            var failure = new CorruptedCaptureStateException(
                "TrafficStream identity does not match its immutable connection route"
            );
            failUnstableProcess(failure);
            return CompletableFuture.failedFuture(failure);
        }
        var payload = CaptureRecord.newBuilder()
            .setTrafficStream(trafficStream)
            .build()
            .toByteArray();
        if (payload.length > payloadSizeLimit) {
            var failure = new IllegalArgumentException(
                "CaptureRecord exceeds the configured Kafka payload limit"
            );
            failPublisher(failure);
            return CompletableFuture.failedFuture(failure);
        }
        var producerRecord = new ProducerRecord<>(
            topic,
            route.partition(),
            null,
            route.connectionId(),
            payload
        );
        return enqueueTrafficSend(
            route,
            producerRecord,
            finalRecord,
            finalRecord
                ? ignored -> {
                    routingState.removeAfterTerminalAcknowledgement(route);
                    beginDrainedWriterRetirements();
                }
                : ignored -> {}
        );
    }

    void validateCriticalMutationTrafficAcknowledgement(
        CaptureRoutingState.ConnectionRoute route,
        RecordMetadata acknowledgement
    ) {
        Objects.requireNonNull(route);
        try {
            if (acknowledgement == null
                || acknowledgement.partition() != route.partition()
                || !acknowledgement.hasTimestamp()) {
                throw new IllegalStateException(
                    "Kafka returned invalid acknowledgement metadata for Critical Mutation Traffic"
                );
            }
            routingState.validateCriticalMutationTrafficAcknowledgement(
                route,
                acknowledgement.timestamp(),
                heartbeatExpirationInterval
            );
        } catch (RuntimeException e) {
            failForThrowable(e);
            throw e;
        }
    }

    CompletableFuture<Void> retireAllWriters() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> beginOrderlyWriterRetirement(result), result);
        return result;
    }

    private void beginOrderlyWriterRetirement(CompletableFuture<Void> result) {
        if (orderlyRetirement != null) {
            orderlyRetirement.whenComplete((ignored, failure) -> completeFrom(null, failure, result));
            return;
        }
        orderlyRetirement = result;
        routingState.beginOrderlyRetirement();
        beginDrainedWriterRetirements();
        var retirements = publisherLanes.values()
            .stream()
            .map(lane -> lane.retirement)
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(retirements)
            .whenComplete((ignored, retirementFailure) ->
                executeCompletionOnPublisher(
                    () -> finishOrderlyWriterRetirement(result, retirementFailure),
                    result
                )
            );
    }

    private void finishOrderlyWriterRetirement(
        CompletableFuture<Void> result,
        Throwable retirementFailure
    ) {
        if (retirementFailure != null) {
            result.completeExceptionally(unwrapCompletionFailure(retirementFailure));
        } else if (!routingState.allWriterPartitionsRetired()) {
            var failure = new CorruptedCaptureStateException(
                "Orderly proxy retirement completed without retiring every writer partition"
            );
            result.completeExceptionally(failure);
            failUnstableProcess(failure);
        } else {
            result.complete(null);
        }
    }

    void abandonUnpublishedConnection(CaptureRoutingState.ConnectionRoute route) {
        routingState.abandonUnpublishedConnection(route);
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            beginDrainedWriterRetirements();
            result.complete(null);
        }, result);
    }

    private CompletableFuture<RecordMetadata> publishHeartbeat(PublisherLane lane) {
        if (lane.status != PublisherLaneStatus.OPEN || lane.heartbeatInFlight) {
            throw new CorruptedCaptureStateException(
                "Heartbeat submission is not permitted for " + lane.writerPartition
            );
        }
        lane.heartbeatInFlight = true;
        if (routingState.lastAcceptedHeartbeatLogAppendTime(lane.writerPartition) == null) {
            renewAcknowledgementDeadline(lane);
        } else if (lane.acknowledgementDeadline == null) {
            throw new CorruptedCaptureStateException(
                "An active heartbeat lane has no acknowledgement deadline for "
                    + lane.writerPartition
            );
        }
        var heartbeat = WriterPartitionHeartbeat.newBuilder()
            .setWriterNodeId(lane.writerPartition.writerNodeId())
            .setHeartbeatIntervalMillis(heartbeatInterval.toMillis())
            .setEmittedAtMillis(clock.millis())
            .build();
        var payload = CaptureRecord.newBuilder()
            .setWriterPartitionHeartbeat(heartbeat)
            .build()
            .toByteArray();
        var producerRecord = new ProducerRecord<>(
            topic,
            lane.writerPartition.partition(),
            null,
            lane.writerPartition.writerNodeId()
                + ":heartbeat:"
                + lane.writerPartition.partition(),
            payload
        );
        return sendFromPublisherThread(
            lane,
            producerRecord,
            lane.acknowledgementDeadline,
            metadata -> acceptHeartbeatAcknowledgement(lane, metadata)
        );
    }

    private void acceptHeartbeatAcknowledgement(PublisherLane lane, RecordMetadata metadata) {
        if (!lane.heartbeatInFlight) {
            throw new CorruptedCaptureStateException(
                "Heartbeat acknowledgement arrived without an in-flight heartbeat for "
                    + lane.writerPartition
            );
        }
        routingState.acceptHeartbeatLogAppendTime(
            lane.writerPartition,
            requireBrokerTimestamp(metadata, "heartbeat"),
            heartbeatExpirationInterval
        );
        lane.heartbeatInFlight = false;
        if (lane.status == PublisherLaneStatus.OPEN) {
            renewAcknowledgementDeadline(lane);
            scheduleNextHeartbeat(lane);
        } else {
            cancelAcknowledgementDeadline(lane);
        }
    }

    private void scheduleNextHeartbeat(PublisherLane lane) {
        if (lane.nextHeartbeat != null) {
            lane.nextHeartbeat.cancel(false);
        }
        lane.nextHeartbeat = executor.schedule(
            () -> {
                lane.nextHeartbeat = null;
                if (lane.status == PublisherLaneStatus.OPEN && failure.get() == null && !closed.get()) {
                    publishHeartbeat(lane);
                }
            },
            heartbeatInterval.toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private void renewAcknowledgementDeadline(PublisherLane lane) {
        cancelAcknowledgementDeadline(lane);
        var deadline = new AcknowledgementDeadline();
        lane.acknowledgementDeadline = deadline;
        deadline.setExpirationTask(acknowledgementDeadlineExecutor.schedule(
            () -> expireAcknowledgementDeadline(lane, deadline),
            heartbeatExpirationInterval.toNanos(),
            TimeUnit.NANOSECONDS
        ));
    }

    private void expireAcknowledgementDeadline(
        PublisherLane lane,
        AcknowledgementDeadline deadline
    ) {
        if (failure.get() != null || closed.get() || !deadline.expire()) {
            return;
        }
        failPublisher(new TimeoutException(
            "Heartbeat acknowledgement deadline expired for " + lane.writerPartition
        ));
    }

    private void cancelAcknowledgementDeadline(PublisherLane lane) {
        var deadline = lane.acknowledgementDeadline;
        lane.acknowledgementDeadline = null;
        if (deadline != null) {
            deadline.cancel();
        }
    }

    private void beginDrainedWriterRetirements() {
        for (var writerPartition : routingState.prepareDrainedWriterRetirements()) {
            var lane = requirePublisherLane(writerPartition);
            if (lane.status != PublisherLaneStatus.OPEN) {
                throw new CorruptedCaptureStateException(
                    "Publisher lane is not open at retirement: " + writerPartition
                );
            }
            lane.status = PublisherLaneStatus.RETIRING;
            if (lane.nextHeartbeat != null) {
                lane.nextHeartbeat.cancel(false);
                lane.nextHeartbeat = null;
            }
            if (!lane.heartbeatInFlight) {
                cancelAcknowledgementDeadline(lane);
            }
            completeLaneRetirementIfReady(lane);
        }
    }

    private void completeLaneRetirementIfReady(PublisherLane lane) {
        if (lane.status != PublisherLaneStatus.RETIRING
            || lane.acceptedSends != 0
            || lane.heartbeatInFlight
            || routingState.hasConnections(lane.writerPartition)) {
            return;
        }
        cancelAcknowledgementDeadline(lane);
        routingState.completeWriterRetirement(lane.writerPartition);
        lane.status = PublisherLaneStatus.RETIRED;
        lane.retirement.complete(null);
        log.atInfo()
            .setMessage("Retired proxy writer {} for partition {}")
            .addArgument(lane.writerPartition.writerNodeId())
            .addArgument(lane.writerPartition.partition())
            .log();
    }

    private CompletableFuture<RecordMetadata> enqueueTrafficSend(
        CaptureRoutingState.ConnectionRoute route,
        ProducerRecord<String, byte[]> producerRecord,
        boolean finalRecord,
        Consumer<RecordMetadata> acknowledgedAction
    ) {
        var result = new CompletableFuture<RecordMetadata>();
        executeOnPublisher(() -> {
            try {
                routingState.acceptTrafficSubmission(route, finalRecord);
                var lane = requirePublisherLane(route.writerPartition());
                sendFromPublisherThread(lane, producerRecord, null, acknowledgedAction)
                    .whenComplete((metadata, throwable) -> completeFrom(metadata, throwable, result));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                failForThrowable(e);
            }
        }, result);
        return result;
    }

    private CompletableFuture<RecordMetadata> sendFromPublisherThread(
        PublisherLane lane,
        ProducerRecord<String, byte[]> producerRecord,
        AcknowledgementDeadline acknowledgementDeadline,
        Consumer<RecordMetadata> acknowledgedAction
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
                addInFlight(lane, result);
                producer.send(
                    producerRecord,
                    (metadata, exception) ->
                        completeSend(
                            lane,
                            result,
                            metadata,
                            exception,
                            acknowledgementDeadline,
                            acknowledgedAction
                        )
                );
            });
        } catch (Throwable t) {
            removeInFlight(lane, result);
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
        PublisherLane lane,
        CompletableFuture<RecordMetadata> result,
        RecordMetadata metadata,
        Exception exception,
        AcknowledgementDeadline acknowledgementDeadline,
        Consumer<RecordMetadata> acknowledgedAction
    ) {
        if (exception != null) {
            failPublisher(exception);
            removeInFlight(lane, result);
            result.completeExceptionally(exception);
            return;
        }
        if (acknowledgementDeadline != null && !acknowledgementDeadline.acknowledge()) {
            var deadlineStatus = acknowledgementDeadline.status();
            removeInFlight(lane, result);
            if (deadlineStatus == AcknowledgementDeadlineStatus.EXPIRED
                || deadlineStatus == AcknowledgementDeadlineStatus.CANCELLED) {
                return;
            }
            failUnstableProcess(new CorruptedCaptureStateException(
                "Kafka invoked the heartbeat acknowledgement callback more than once for "
                    + lane.writerPartition
            ));
            return;
        }
        try {
            executor.execute(() -> {
                if (result.isDone()) {
                    removeInFlight(lane, result);
                    return;
                }
                try {
                    acknowledgedAction.accept(metadata);
                    removeInFlight(lane, result);
                    result.complete(metadata);
                    completeLaneRetirementIfReady(lane);
                } catch (Throwable t) {
                    removeInFlight(lane, result);
                    failForThrowable(t);
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            removeInFlight(lane, result);
            result.completeExceptionally(e);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
    }

    private void addInFlight(
        PublisherLane lane,
        CompletableFuture<RecordMetadata> result
    ) {
        lane.acceptedSends++;
        synchronized (inFlightLock) {
            inFlightSends.add(result);
        }
    }

    private void removeInFlight(
        PublisherLane lane,
        CompletableFuture<RecordMetadata> result
    ) {
        synchronized (inFlightLock) {
            if (!inFlightSends.remove(result)) {
                return;
            }
        }
        lane.acceptedSends--;
        if (lane.acceptedSends < 0) {
            throw new CorruptedCaptureStateException(
                "Publisher accepted-send count became negative for " + lane.writerPartition
            );
        }
    }

    private PublisherLane requirePublisherLane(
        CaptureRoutingState.WriterPartition writerPartition
    ) {
        var lane = publisherLanes.get(writerPartition);
        if (lane == null) {
            throw new CorruptedCaptureStateException(
                "No publisher lane exists for " + writerPartition
            );
        }
        return lane;
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

    private void executeCompletionOnPublisher(Runnable action, CompletableFuture<?> result) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
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
        runFailureCleanupOnPublisherThread(throwable);
        log.atError()
            .setCause(throwable)
            .setMessage(
                "Capture Kafka publisher stopped permanently after failure; "
                    + "no more records will be submitted"
            )
            .log();
    }

    private void runFailureCleanupOnPublisherThread(Throwable throwable) {
        Runnable cleanup = () -> {
            publisherLanes.values().forEach(lane -> {
                if (lane.nextHeartbeat != null) {
                    lane.nextHeartbeat.cancel(false);
                    lane.nextHeartbeat = null;
                }
                cancelAcknowledgementDeadline(lane);
                if (!lane.retirement.isDone()) {
                    lane.retirement.completeExceptionally(throwable);
                }
            });
            assignmentInstallationInProgress = false;
            AssignmentInstallation installation;
            while ((installation = assignmentInstallations.pollFirst()) != null) {
                installation.result().completeExceptionally(throwable);
            }
        };
        if (Thread.currentThread() == publisherThread.get()) {
            cleanup.run();
            return;
        }
        try {
            executor.execute(cleanup);
        } catch (RejectedExecutionException e) {
            log.atError()
                .setCause(e)
                .setMessage("Publisher executor rejected terminal failure cleanup")
                .log();
            failUnstableProcess(e);
        }
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

    private static long requireBrokerTimestamp(RecordMetadata metadata, String recordKind) {
        if (metadata == null || !metadata.hasTimestamp() || metadata.timestamp() <= 0) {
            throw new IllegalStateException(
                "Kafka did not report a positive LogAppendTime for an acknowledged " + recordKind
            );
        }
        return metadata.timestamp();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static <T> void completeFrom(
        T value,
        Throwable throwable,
        CompletableFuture<T> result
    ) {
        if (throwable == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(unwrapCompletionFailure(throwable));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        routingState.beginShutdown();
        try {
            var flush = new CompletableFuture<Void>();
            try {
                executor.execute(() -> {
                    try {
                        publisherLanes.values().forEach(lane -> {
                            if (lane.nextHeartbeat != null) {
                                lane.nextHeartbeat.cancel(false);
                            }
                            cancelAcknowledgementDeadline(lane);
                        });
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
            acknowledgementDeadlineExecutor.shutdownNow();
            try {
                if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
                if (!acknowledgementDeadlineExecutor.awaitTermination(
                    CLOSE_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
                )) {
                    acknowledgementDeadlineExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                acknowledgementDeadlineExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                producer.close(CLOSE_TIMEOUT);
            }
        }
    }

}
