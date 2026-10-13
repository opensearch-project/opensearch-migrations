package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.FailureReason;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.PublishOutcome;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.RecordKind;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.RoutingTransition;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriterPartitionHeartbeat;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Serializes all capture publication state on one publisher thread. Each writer identity and Kafka
 * partition has a distinct lane that orders its initial heartbeat, connection traffic, periodic
 * heartbeats, acknowledgement deadlines, and final retirement without relying on producer
 * callbacks to arrive on that thread.
 *
 * <p>A routing generation becomes eligible for new connections only after every lane's initial
 * heartbeat is acknowledged with acceptable broker time. Replacing that generation stops new
 * routing through its lanes but does not stop their publication: superseded lanes continue traffic
 * and heartbeats while existing connections drain, then retire locally once no accepted send or
 * connection remains. Any failure that makes acknowledged ordering or heartbeat continuity
 * uncertain permanently closes the process-wide write gate.
 *
 * <p>{@link KafkaCaptureFactory} creates this object after the traffic topic has passed its
 * capability probes and transfers ownership of the Kafka producer to it. {@link
 * CaptureKafkaMembership} then asks it to initialize routing generations as Kafka membership
 * changes. The factory does not expose capture to new connections until the first generation's
 * initial heartbeats have completed; after that, connection serializers submit traffic through
 * their immutable {@link CaptureRoutingState.ConnectionRoute}.
 *
 * <p>The factory also manages shutdown around this object. Orderly shutdown first stops membership
 * changes and waits for connections to drain, then asks the publisher to retire every
 * writer-partition lane before {@link #close()} flushes and closes the producer and executors. A
 * publication or heartbeat-continuity failure takes the shorter failure path: the shared write
 * gate closes immediately and all pending work is failed because the process can no longer claim
 * authoritative capture. These responsibilities implement the publisher ownership, heartbeat,
 * and retirement rules in Capture and Replay Architecture §§4–7 and Proxy Capture Protocol
 * §§4.2–5.4.
 */
@Slf4j
public class CaptureKafkaPublisher implements CaptureRoutingGenerationPublisher, AutoCloseable {
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration HEALTH_REPORT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Local lifecycle of one {@code (writerNodeId, partition)} publisher lane. This is separate
     * from routing-generation state: a lane can remain {@link #OPEN} after its generation stops
     * receiving new connections because existing connections still need traffic publication and
     * heartbeats while they drain.
     */
    private enum PublisherLaneStatus {
        /** Accepts publication and schedules heartbeats for a current or draining writer. */
        OPEN,

        /** Accepts no new work and waits for already accepted sends and callbacks to quiesce. */
        RETIRING,

        /** Permanently quiet; the writer-partition pair can never publish again. */
        RETIRED
    }

    /**
     * Result of the race required by Proxy Capture Protocol §5.2 between Kafka's heartbeat
     * acknowledgement callback, the independent monotonic deadline scheduler, and lifecycle
     * cleanup. Only one transition out of {@link #PENDING} is allowed.
     */
    private enum AcknowledgementDeadlineStatus {
        /** Neither the acknowledgement callback nor a terminal deadline action has won. */
        PENDING,

        /**
         * Kafka's callback arrived first. The callback may now be handed to the publisher thread
         * to validate its broker timestamp and advance the heartbeat baseline.
         */
        ACKNOWLEDGED,

        /**
         * The local {@code E} deadline won. Capture is permanently compromised; a later callback
         * must not update the heartbeat baseline, activate routing, or complete retirement.
         */
        EXPIRED,

        /**
         * The deadline was deliberately disarmed because its lane or publisher is ending. This is
         * not a liveness failure: it tells a racing late callback to perform no state transition.
         */
        CANCELLED
    }

    /**
     * Cross-thread arbiter for one heartbeat acknowledgement interval.
     *
     * <p>The Kafka callback and the dedicated deadline executor do not run on the publisher
     * thread, so cancelling only the scheduled task would leave a race in which both sides believe
     * they succeeded. The atomic status is the authoritative decision. The scheduled future is
     * retained only so the losing timer can be removed promptly; {@link #setExpirationTask} also
     * handles acknowledgement or cancellation winning before the scheduler returns the future.
     */
    private static final class AcknowledgementDeadline {
        private final AtomicReference<AcknowledgementDeadlineStatus> status =
            new AtomicReference<>(AcknowledgementDeadlineStatus.PENDING);
        private final AtomicReference<ScheduledFuture<?>> expirationTask = new AtomicReference<>();

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
            this.expirationTask.set(expirationTask);
            if (status.get() != AcknowledgementDeadlineStatus.PENDING) {
                expirationTask.cancel(false);
            }
        }

        private AcknowledgementDeadlineStatus status() {
            return status.get();
        }

        private void cancelExpirationTask() {
            var task = expirationTask.get();
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    /**
     * Publication and retirement bookkeeping for one writer identity on one Kafka partition.
     *
     * <p>The lane is the implementation of the single-owner publisher rule in Proxy Capture
     * Protocol §4.2. {@code heartbeatInFlight} prevents overlapping heartbeats, {@code
     * acknowledgementDeadline} proves heartbeat continuity, and {@code acceptedSends} plus the
     * connection registry form the §4.3 retirement barrier. The {@code retirement} future resolves
     * only after the lane is permanently quiet.
     */
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

    /**
     * A Kafka membership snapshot waiting to become a routing generation. Its result is completed
     * only after every new writer-partition lane has an accepted initial heartbeat, which is the
     * generation-wide acceptance gate described by Proxy Capture Protocol §5.3.
     */
    private static final class RoutingGenerationInitialization {
        private final List<Integer> partitions;
        private final CompletableFuture<String> result;

        private RoutingGenerationInitialization(
            List<Integer> partitions,
            CompletableFuture<String> result
        ) {
            this.partitions = partitions;
            this.result = result;
        }

        private List<Integer> partitions() {
            return partitions;
        }

        private CompletableFuture<String> result() {
            return result;
        }
    }

    private static final class InFlightPublication {
        private final PublisherLane lane;
        private final CompletableFuture<RecordMetadata> result;
        private final RecordKind kind;
        private final int bytes;
        private final long acceptedAtMillis;

        private InFlightPublication(
            PublisherLane lane,
            CompletableFuture<RecordMetadata> result,
            RecordKind kind,
            int bytes,
            long acceptedAtMillis
        ) {
            this.lane = lane;
            this.result = result;
            this.kind = kind;
            this.bytes = bytes;
            this.acceptedAtMillis = acceptedAtMillis;
        }
    }

    // Producer, protocol configuration, and process-level failure integration.
    private final Producer<String, byte[]> producer;
    private final String topic;
    @Getter
    private final CaptureRoutingState routingState;
    private final CaptureKafkaWriteGate writeGate;
    private final CapturePublishingMetrics metrics;
    private final Consumer<Throwable> unstableProcessFailureCallback;
    private final int payloadSizeLimit;
    private final Duration heartbeatInterval;
    private final Duration heartbeatExpirationInterval;
    private final Clock clock;

    // The publisher executor owns ordered state transitions. The separate deadline executor must
    // remain responsive even if publisher work is queued, so publisher backlog cannot win a
    // heartbeat-deadline race by delaying the timer.
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor acknowledgementDeadlineExecutor;
    private final AtomicReference<Thread> publisherThread = new AtomicReference<>();

    // Routing-generation and lane lifecycle state, normally advanced on the publisher executor.
    private final Map<CaptureRoutingState.WriterPartition, PublisherLane> publisherLanes =
        new HashMap<>();
    private final ArrayDeque<RoutingGenerationInitialization> routingGenerationInitializations =
        new ArrayDeque<>();

    // Terminal state and producer callbacks cross executor boundaries. The atomics and in-flight
    // registry make failure and close visible immediately without permitting another write.
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean unstableProcessFailureReported = new AtomicBoolean();
    private final Object inFlightLock = new Object();
    private final Map<CompletableFuture<RecordMetadata>, InFlightPublication> inFlightSends =
        new IdentityHashMap<>();
    private CompletableFuture<Void> orderlyRetirement;
    private boolean routingGenerationInitializationInProgress;

    public CaptureKafkaPublisher(
        Producer<String, byte[]> producer,
        String topic,
        CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        CapturePublishingMetrics metrics,
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
            metrics,
            new CaptureKafkaWriteGate(metrics),
            unstableProcessFailureCallback
        );
    }

    CaptureKafkaPublisher(
        @NonNull Producer<String, byte[]> producer,
        @NonNull String topic,
        @NonNull CaptureRoutingState routingState,
        int maximumKafkaMessageSize,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        @NonNull Clock clock,
        @NonNull CapturePublishingMetrics metrics,
        @NonNull CaptureKafkaWriteGate writeGate,
        @NonNull Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.producer = producer;
        this.topic = topic;
        this.routingState = routingState;
        this.clock = clock;
        this.metrics = metrics;
        this.writeGate = writeGate;
        this.unstableProcessFailureCallback = unstableProcessFailureCallback;
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
        writeGate.addTerminalFailureListener(terminalFailure ->
            failPublisher(terminalFailure, classifyFailure(terminalFailure))
        );
        executor.scheduleWithFixedDelay(
            this::reportHealthSafely,
            HEALTH_REPORT_INTERVAL.toNanos(),
            HEALTH_REPORT_INTERVAL.toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    @Override
    public CompletableFuture<String> initializeRoutingGeneration(
        @NonNull Collection<Integer> partitions
    ) {
        var result = new CompletableFuture<String>();
        var partitionSnapshot = List.copyOf(partitions);
        executeOnPublisher(() -> {
            routingGenerationInitializations.addLast(
                new RoutingGenerationInitialization(partitionSnapshot, result)
            );
            startNextRoutingGenerationInitialization();
        }, result);
        return result;
    }

    private void startNextRoutingGenerationInitialization() {
        if (routingGenerationInitializationInProgress
            || routingGenerationInitializations.isEmpty()) {
            return;
        }
        routingGenerationInitializationInProgress = true;
        var initialization = routingGenerationInitializations.removeFirst();
        initializeRoutingGenerationOnPublisher(
            initialization.partitions(),
            initialization.result()
        );
    }

    private void initializeRoutingGenerationOnPublisher(
        Collection<Integer> partitions,
        CompletableFuture<String> result
    ) {
        var pendingGeneration = routingState.prepareRoutingGeneration(partitions);
        metrics.routingTransition(RoutingTransition.GENERATION_PREPARED);
        log.atInfo()
            .setMessage(
                "event=capture_generation_prepared writerNodeId={} generation={} partitions={}"
            )
            .addArgument(pendingGeneration.writerNodeId())
            .addArgument(pendingGeneration.generationSequence())
            .addArgument(pendingGeneration.partitions())
            .log();
        var initialHeartbeats = pendingGeneration.writerPartitions()
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
            .whenComplete((ignored, generationFailure) ->
                executeCompletionOnPublisher(
                    () -> finishRoutingGenerationInitialization(
                        pendingGeneration,
                        result,
                        generationFailure
                    ),
                    result
                )
            );
    }

    private void finishRoutingGenerationInitialization(
        CaptureRoutingState.PendingRoutingGeneration pendingGeneration,
        CompletableFuture<String> result,
        Throwable generationFailure
    ) {
        if (generationFailure != null) {
            var cause = unwrapCompletionFailure(generationFailure);
            result.completeExceptionally(cause);
            failForThrowable(cause);
            return;
        }
        try {
            var previousWriterNodeId = routingState.currentWriterNodeId();
            var previousPartitions = routingState.activeRoutingPartitions();
            routingState.activateRoutingGeneration(pendingGeneration);
            metrics.routingTransition(RoutingTransition.GENERATION_ACTIVATED);
            metrics.writersActivated(
                pendingGeneration.partitions().size(),
                previousPartitions.size()
            );
            if (previousWriterNodeId != null) {
                log.atInfo()
                    .setMessage(
                        "event=capture_writer_draining writerNodeId={} partitions={} reason=replaced"
                    )
                    .addArgument(previousWriterNodeId)
                    .addArgument(previousPartitions)
                    .log();
            }
            log.atInfo()
                .setMessage(
                    "event=capture_generation_activated writerNodeId={} generation={} partitions={}"
                )
                .addArgument(pendingGeneration.writerNodeId())
                .addArgument(pendingGeneration.generationSequence())
                .addArgument(pendingGeneration.partitions())
                .log();
            result.complete(pendingGeneration.writerNodeId());
            beginDrainedWriterRetirements();
            routingGenerationInitializationInProgress = false;
            startNextRoutingGenerationInitialization();
        } catch (Throwable t) {
            result.completeExceptionally(t);
            failForThrowable(t);
        }
    }

    public CompletableFuture<RecordMetadata> publishTraffic(
        @NonNull CaptureRoutingState.ConnectionRoute route,
        @NonNull TrafficStream trafficStream,
        boolean finalRecord
    ) {
        if (!route.writerNodeId().equals(trafficStream.getNodeId())
            || !route.connectionId().equals(trafficStream.getConnectionId())) {
            var identityFailure = new CorruptedCaptureStateException(
                "TrafficStream identity does not match its immutable connection route"
            );
            failUnstableProcess(identityFailure);
            return CompletableFuture.failedFuture(identityFailure);
        }
        var payload = CaptureRecord.newBuilder()
            .setTrafficStream(trafficStream)
            .build()
            .toByteArray();
        if (payload.length > payloadSizeLimit) {
            var payloadFailure = new IllegalArgumentException(
                "CaptureRecord exceeds the configured Kafka payload limit"
            );
            failPublisher(payloadFailure, FailureReason.PAYLOAD_LIMIT);
            return CompletableFuture.failedFuture(payloadFailure);
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
        @NonNull CaptureRoutingState.ConnectionRoute route,
        RecordMetadata acknowledgement
    ) {
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
            reportBrokerTimeViolation(route.writerPartition(), e);
            throw e;
        }
    }

    public CaptureRoutingState.ConnectionRoute routeNewConnection(
        @NonNull String connectionId
    ) {
        var route = routingState.routeNewConnection(connectionId);
        log.atDebug()
            .setMessage(
                "event=capture_route_selected writerNodeId={} partition={} connectionId={}"
            )
            .addArgument(route.writerNodeId())
            .addArgument(route.partition())
            .addArgument(route.connectionId())
            .log();
        return route;
    }

    CompletableFuture<Void> retireAllWriters() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> beginOrderlyWriterRetirement(result), result);
        return result;
    }

    private void beginOrderlyWriterRetirement(CompletableFuture<Void> result) {
        if (orderlyRetirement != null) {
            orderlyRetirement.whenComplete(
                (ignored, existingRetirementFailure) ->
                    completeFrom(null, existingRetirementFailure, result)
            );
            return;
        }
        orderlyRetirement = result;
        var currentWriterNodeId = routingState.currentWriterNodeId();
        var currentPartitions = routingState.activeRoutingPartitions();
        routingState.beginOrderlyRetirement();
        metrics.activeWritersBeganDraining(currentPartitions.size());
        if (currentWriterNodeId != null) {
            log.atInfo()
                .setMessage(
                    "event=capture_writer_draining writerNodeId={} partitions={} reason=shutdown"
                )
                .addArgument(currentWriterNodeId)
                .addArgument(currentPartitions)
                .log();
        }
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
            var retirementStateFailure = new CorruptedCaptureStateException(
                "Orderly proxy retirement completed without retiring every writer partition"
            );
            result.completeExceptionally(retirementStateFailure);
            failUnstableProcess(retirementStateFailure);
        } else {
            result.complete(null);
        }
    }

    void abandonUnpublishedConnection(CaptureRoutingState.ConnectionRoute route) {
        routingState.abandonUnpublishedConnection(route);
        log.atWarn()
            .setMessage(
                "event=capture_connection_dropped reason=abandoned_before_publication "
                    + "writerNodeId={} partition={} connectionId={}"
            )
            .addArgument(route.writerNodeId())
            .addArgument(route.partition())
            .addArgument(route.connectionId())
            .log();
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
        var startAcknowledgementDeadlineAfterSubmission = false;
        if (routingState.lastAcceptedHeartbeatLogAppendTime(lane.writerPartition) == null) {
            replaceAcknowledgementDeadline(lane);
            startAcknowledgementDeadlineAfterSubmission = true;
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
            RecordKind.HEARTBEAT,
            lane.acknowledgementDeadline,
            startAcknowledgementDeadlineAfterSubmission,
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
        try {
            routingState.acceptHeartbeatLogAppendTime(
                lane.writerPartition,
                requireBrokerTimestamp(metadata, "heartbeat"),
                heartbeatExpirationInterval
            );
        } catch (RuntimeException brokerTimeFailure) {
            reportBrokerTimeViolation(lane.writerPartition, brokerTimeFailure);
            throw brokerTimeFailure;
        }
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
        var deadline = replaceAcknowledgementDeadline(lane);
        startAcknowledgementDeadline(lane, deadline);
    }

    private AcknowledgementDeadline replaceAcknowledgementDeadline(PublisherLane lane) {
        cancelAcknowledgementDeadline(lane);
        var deadline = new AcknowledgementDeadline();
        lane.acknowledgementDeadline = deadline;
        return deadline;
    }

    private void startAcknowledgementDeadline(
        PublisherLane lane,
        AcknowledgementDeadline deadline
    ) {
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
        metrics.heartbeatDeadlineViolated();
        log.atWarn()
            .setMessage(
                "event=capture_heartbeat_late writerNodeId={} partition={} deadlineMillis={}"
            )
            .addArgument(lane.writerPartition.writerNodeId())
            .addArgument(lane.writerPartition.partition())
            .addArgument(heartbeatExpirationInterval.toMillis())
            .log();
        failPublisher(
            new TimeoutException(
                "Heartbeat acknowledgement deadline expired for " + lane.writerPartition
            ),
            FailureReason.HEARTBEAT_DEADLINE
        );
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
        metrics.writerRetired();
        log.atInfo()
            .setMessage("event=capture_writer_retired writerNodeId={} partition={}")
            .addArgument(lane.writerPartition.writerNodeId())
            .addArgument(lane.writerPartition.partition())
            .log();
    }

    CompletableFuture<Void> reportHealthNow() {
        var result = new CompletableFuture<Void>();
        executeOnPublisher(() -> {
            reportHealth();
            result.complete(null);
        }, result);
        return result;
    }

    // Any Throwable escaping this fixed-delay task would silently cancel every later health report.
    @SuppressWarnings("java:S1181")
    private void reportHealthSafely() {
        try {
            reportHealth();
        } catch (Throwable healthReportFailure) {
            log.atError()
                .setCause(healthReportFailure)
                .setMessage("event=capture_health_report_failed")
                .log();
        }
    }

    private void reportHealth() {
        var routing = routingState.diagnosticSnapshot();
        int inFlightRecords;
        long inFlightBytes;
        long oldestAcceptedAtMillis = Long.MAX_VALUE;
        synchronized (inFlightLock) {
            inFlightRecords = inFlightSends.size();
            inFlightBytes = inFlightSends.values()
                .stream()
                .mapToLong(publication -> publication.bytes)
                .sum();
            for (var publication : inFlightSends.values()) {
                oldestAcceptedAtMillis = Math.min(
                    oldestAcceptedAtMillis,
                    publication.acceptedAtMillis
                );
            }
        }
        var oldestUnacknowledgedAgeMillis = oldestAcceptedAtMillis == Long.MAX_VALUE
            ? 0
            : Math.max(0, clock.millis() - oldestAcceptedAtMillis);
        log.atInfo()
            .setMessage(
                "event=capture_health activeWriters={} drainingWriters={} retiringWriters={} "
                    + "retiredWriters={} partitions={} activeConnections={} inFlightRecords={} "
                    + "inFlightBytes={} oldestUnacknowledgedAgeMillis={} "
                    + "lastHeartbeatBrokerTime={} publisherQueueDepth={} writeGate={}"
            )
            .addArgument(routing.activeWriters())
            .addArgument(routing.drainingWriters())
            .addArgument(routing.retiringWriters())
            .addArgument(routing.retiredWriters())
            .addArgument(routing.activePartitions())
            .addArgument(routing.activeConnections())
            .addArgument(inFlightRecords)
            .addArgument(inFlightBytes)
            .addArgument(oldestUnacknowledgedAgeMillis)
            .addArgument(() -> routing.lastHeartbeatBrokerTime()
                .stream()
                .mapToObj(Long::toString)
                .findFirst()
                .orElse("none"))
            .addArgument(
                executor.getQueue()
                    .stream()
                    .filter(task -> !(task instanceof java.util.concurrent.Delayed delayed)
                        || delayed.getDelay(TimeUnit.NANOSECONDS) <= 0)
                    .count()
            )
            .addArgument(writeGate.failureIfNotWritable() == null ? "open" : "closed")
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
                sendFromPublisherThread(
                    lane,
                    producerRecord,
                    RecordKind.TRAFFIC,
                    null,
                    false,
                    acknowledgedAction
                )
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
        RecordKind recordKind,
        AcknowledgementDeadline acknowledgementDeadline,
        boolean startAcknowledgementDeadlineAfterSubmission,
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
                addInFlight(lane, result, recordKind, producerRecord.value().length);
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
                if (startAcknowledgementDeadlineAfterSubmission) {
                    startAcknowledgementDeadline(lane, acknowledgementDeadline);
                }
            });
        } catch (Throwable t) {
            removeInFlight(result, PublishOutcome.FAILED);
            failForThrowable(t);
            result.completeExceptionally(t);
            return result;
        }
        var rejection = gateRejection == null ? publisherRejection.get() : gateRejection;
        if (rejection != null) {
            if (gateRejection == null) {
                metrics.writeRejected();
            }
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
            failPublisher(exception, FailureReason.KAFKA_SEND);
            return;
        }
        if (acknowledgementDeadline != null && !acknowledgementDeadline.acknowledge()) {
            var deadlineStatus = acknowledgementDeadline.status();
            removeInFlight(result, PublishOutcome.FAILED);
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
                    removeInFlight(result, PublishOutcome.FAILED);
                    return;
                }
                try {
                    acknowledgedAction.accept(metadata);
                    removeInFlight(result, PublishOutcome.ACKNOWLEDGED);
                    result.complete(metadata);
                    completeLaneRetirementIfReady(lane);
                } catch (Throwable t) {
                    removeInFlight(result, PublishOutcome.FAILED);
                    failForThrowable(t);
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            removeInFlight(result, PublishOutcome.FAILED);
            result.completeExceptionally(e);
            if (!closed.get()) {
                failUnstableProcess(e);
            }
        }
    }

    private void addInFlight(
        PublisherLane lane,
        CompletableFuture<RecordMetadata> result,
        RecordKind kind,
        int bytes
    ) {
        lane.acceptedSends++;
        synchronized (inFlightLock) {
            inFlightSends.put(
                result,
                new InFlightPublication(lane, result, kind, bytes, clock.millis())
            );
        }
        metrics.publicationAccepted(kind, bytes);
    }

    private void removeInFlight(
        CompletableFuture<RecordMetadata> result,
        PublishOutcome outcome
    ) {
        InFlightPublication publication;
        synchronized (inFlightLock) {
            publication = inFlightSends.remove(result);
            if (publication == null) {
                return;
            }
        }
        publication.lane.acceptedSends--;
        if (publication.lane.acceptedSends < 0) {
            throw new CorruptedCaptureStateException(
                "Publisher accepted-send count became negative for "
                    + publication.lane.writerPartition
            );
        }
        metrics.publicationCompleted(publication.kind, outcome, publication.bytes);
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

    @SuppressWarnings("java:S1181") // Errors must cross this executor boundary into unstable-process handling.
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
        failPublisher(throwable, classifyFailure(throwable));
    }

    private void failPublisher(Throwable throwable, FailureReason reason) {
        if (!failure.compareAndSet(null, throwable)) {
            return;
        }
        metrics.publisherFailed(reason);
        writeGate.trip(throwable, reason);
        List<InFlightPublication> pending;
        synchronized (inFlightLock) {
            pending = List.copyOf(inFlightSends.values());
            inFlightSends.clear();
        }
        pending.forEach(publication -> {
            metrics.publicationCompleted(
                publication.kind,
                PublishOutcome.FAILED,
                publication.bytes
            );
            publication.result.completeExceptionally(throwable);
        });
        runFailureCleanupOnPublisherThread(throwable);
        log.atWarn()
            .setMessage(
                "event=capture_write_gate_changed state=closed reason={}"
            )
            .addArgument(reason.name().toLowerCase(java.util.Locale.ROOT))
            .log();
        log.atError()
            .setCause(throwable)
            .setMessage(
                "event=capture_publisher_failed reason={} inFlightRecords={} "
                    + "message=Capture_Kafka_publisher_stopped_permanently"
            )
            .addArgument(reason.name().toLowerCase(java.util.Locale.ROOT))
            .addArgument(pending.size())
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
            routingGenerationInitializationInProgress = false;
            RoutingGenerationInitialization initialization;
            while ((initialization = routingGenerationInitializations.pollFirst()) != null) {
                initialization.result().completeExceptionally(throwable);
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
    public void stopAfterFailure(@NonNull Throwable throwable) {
        failPublisher(throwable);
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

    private void reportBrokerTimeViolation(
        CaptureRoutingState.WriterPartition writerPartition,
        RuntimeException failure
    ) {
        metrics.brokerTimeViolated();
        log.atWarn()
            .setCause(failure)
            .setMessage(
                "event=capture_broker_time_violation writerNodeId={} partition={}"
            )
            .addArgument(writerPartition.writerNodeId())
            .addArgument(writerPartition.partition())
            .log();
        failPublisher(failure, FailureReason.BROKER_TIME);
    }

    private static FailureReason classifyFailure(Throwable failure) {
        if (failure instanceof TimeoutException) {
            return FailureReason.HEARTBEAT_DEADLINE;
        }
        if (failure instanceof CorruptedCaptureStateException || failure instanceof Error) {
            return FailureReason.CORRUPTED_STATE;
        }
        if (failure instanceof RejectedExecutionException) {
            return FailureReason.EXECUTOR_REJECTED;
        }
        return FailureReason.OTHER;
    }

    private static long requireBrokerTimestamp(RecordMetadata metadata, String recordKind) {
        if (metadata == null || !metadata.hasTimestamp() || metadata.timestamp() <= 0) {
            throw new IllegalStateException(
                "Kafka did not report a positive LogAppendTime for an acknowledged " + recordKind
            );
        }
        return metadata.timestamp();
    }

    private static Duration requirePositive(@NonNull Duration value, String name) {
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
