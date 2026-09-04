package org.opensearch.migrations.replay.kafka;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionPartitionGenerationKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceControlRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.lifecycle.SourceRunwayLostException;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey;
import org.opensearch.migrations.replay.traffic.source.FollowUpRequirement;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ScanEvidence;
import org.opensearch.migrations.replay.traffic.source.SourceControlEvent;
import org.opensearch.migrations.replay.traffic.source.SourceInput;
import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * Adapt a Kafka stream into a TrafficCaptureSource.
 *
 * Notice that there's a critical gap between how Kafka accepts commits and how the
 * BlockingTrafficSource throttles calls to Kafka.  The BlockingTrafficSource may
 * block calls to readNextTrafficStreamChunk() until some time window elapses.  This
 * could be a very large window in cases where there were long gaps between recorded
 * requests from the capturing proxy.  For example, if a TrafficStream is read and if
 * that stream is scheduled to be run one hour later, readNextTrafficStreamChunk()
 * may not be called for almost an hour.  By design, we're not calling Kafka to pull
 * any more messages since we know that we don't have work to do for an hour.  Shortly
 * after the hour of waiting begins, Kakfa will notice that this application is no
 * longer calling poll and will kick the consumer out of the client group.
 *
 * See
 * <a href="https://kafka.apache.org/21/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html#failuredetection">...</a>
 *
 * "Basically if you don't call poll at least as frequently as the configured max interval,
 * then the client will proactively leave the group so that another consumer can take
 * over its partitions. When this happens, you may see an offset commit failure (as
 * indicated by a CommitFailedException thrown from a call to commitSync())."
 *
 * Since the Kafka client requires all calls to be made from the same thread, we can't
 * simply run a background job to keep the client warm.  We need the caller to touch
 * this object periodically to keep the connection alive.
 */
@Slf4j
public class KafkaTrafficCaptureSource implements ISimpleTrafficCaptureSource {
    private static final Duration LIVENESS_SCAN_INTERVAL = Duration.ofSeconds(1);
    private static final Duration LIVENESS_SCAN_BUDGET = Duration.ofMillis(250);
    private static final int MAX_LIVENESS_SCAN_RECORDS = 10_000;

    private record ActiveConnectionScanState(
        SourcePartitionKey partition,
        String routingPlanId,
        long lastReplayedOffset,
        FollowUpRequirement requirement
    ) {
        ActiveConnectionScanState withRequirement(FollowUpRequirement newRequirement) {
            return new ActiveConnectionScanState(
                partition,
                routingPlanId,
                lastReplayedOffset,
                newRequirement
            );
        }
    }

    public static final String MAX_POLL_INTERVAL_KEY = "max.poll.interval.ms";
    // Match the kafka-clients library default (5 minutes). This is the broker-enforced fence
    // threshold — how long the consumer can go between poll() calls before the group coordinator
    // reassigns its partitions. PR #3013 made round-trip reassignment handling correct, so the
    // historical "fence aggressively" rationale (the previous 60s override) is no longer needed.
    // The TOUCH frequency that keeps us inside this window is decoupled from the fence value and
    // pinned in DEFAULT_KEEP_ALIVE_PERIOD below; raising the fence threshold does NOT slow down
    // our heartbeat. Operators can still override via --kafkaPropertyFile, and subclasses can
    // override via {@link #defaultPollIntervalMs()} (note: a static field would be hidden, not
    // overridden — the value is exposed through a method so subclass intent is honored).
    public static final String DEFAULT_POLL_INTERVAL_MS = "300000";

    /**
     * Default value for {@code max.poll.interval.ms} when no operator-supplied properties file
     * sets it. Subclasses may override to vary the broker-enforced fence threshold.
     */
    protected static String defaultPollIntervalMs() {
        return DEFAULT_POLL_INTERVAL_MS;
    }

    // Touch period used to keep the consumer inside the max.poll.interval.ms window when the read
    // loop is back-pressured. Pinned to 30s so behavior matches what shipped with the historical
    // 60s default (60s / 2 = 30s). Decoupling this from max.poll.interval.ms preserves the
    // tight heartbeat cadence while letting the fence threshold be more lenient.
    static final Duration DEFAULT_KEEP_ALIVE_PERIOD = Duration.ofSeconds(30);

    final TrackingKafkaConsumer trackingKafkaConsumer;
    private final ExecutorService kafkaExecutor;
    private final AtomicLong trafficStreamsRead;
    private final KafkaBehavioralPolicy behavioralPolicy;
    private final ChannelContextManager channelContextManager;
    private final AtomicBoolean isClosed;
    private final Clock clock;
    private final KafkaLivenessScanner livenessScanner = new KafkaLivenessScanner();
    private final AtomicLong nextLivenessScanAtMillis = new AtomicLong();
    private final ConcurrentHashMap<ITrafficStreamKey, CompletableFuture<Void>> pendingCommitAcknowledgements =
        new ConcurrentHashMap<>();
    /** Active connections per Kafka partition. Entries removed when connections are closed */
    final ConcurrentHashMap<Integer, Set<ScopedConnectionIdKey>> partitionToActiveConnections =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScopedConnectionIdKey, ActiveConnectionScanState> activeConnectionScanStates =
        new ConcurrentHashMap<>();
    private final Set<SourceConnectionPartitionGenerationKey> pendingConfirmedDead =
        ConcurrentHashMap.newKeySet();
    private final Queue<SourceControlEvent.ConfirmedDead> sourceControlQueue = new ConcurrentLinkedQueue<>();
    /** Batches of synthetic close events to drain before returning real Kafka records.
     *  Each entry is one batch from a single partition-revocation event. */
    private final Queue<List<TrafficSourceReaderInterruptedClose>> trafficSourceReaderInterruptedCloseQueue = new ConcurrentLinkedQueue<>();
    static final class SessionTerminationObligation {
        private final int partition;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        SessionTerminationObligation(int partition) {
            this.partition = partition;
        }

        int partition() {
            return partition;
        }

        CompletionStage<Void> completion() {
            return completion.minimalCompletionStage();
        }

        boolean acknowledge() {
            return completion.complete(null);
        }

        void fail(Throwable cause) {
            completion.completeExceptionally(cause);
        }
    }

    final ConcurrentHashMap<SourceConnectionPartitionGenerationKey, SessionTerminationObligation>
        pendingSessionTerminationObligations = new ConcurrentHashMap<>();

    public KafkaTrafficCaptureSource(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval
    ) {
        this(globalContext, kafkaConsumer, topic, keepAliveInterval, Clock.systemUTC(), new KafkaBehavioralPolicy());
    }

    public KafkaTrafficCaptureSource(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        @NonNull String topic,
        Duration keepAliveInterval,
        Clock clock,
        @NonNull KafkaBehavioralPolicy behavioralPolicy
    ) {
        this.channelContextManager = new ChannelContextManager(globalContext);
        trackingKafkaConsumer = new TrackingKafkaConsumer(
            globalContext,
            kafkaConsumer,
            topic,
            keepAliveInterval,
            clock,
            this::onKeyFinishedCommitting
        );
        trafficStreamsRead = new AtomicLong();
        this.behavioralPolicy = behavioralPolicy;
        this.clock = clock;
        kafkaConsumer.subscribe(Collections.singleton(topic), trackingKafkaConsumer);
        kafkaExecutor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("kafkaConsumerThread"));
        isClosed = new AtomicBoolean(false);
        // Register callback: when partitions are truly lost, enqueue synthetic closes for their active connections
        trackingKafkaConsumer.setOnPartitionsTrulyLostCallback(this::enqueueTrafficSourceReaderInterruptedClosesForPartitions);
    }

    private void enqueueTrafficSourceReaderInterruptedClosesForPartitions(
        Collection<SourcePartitionKey> lostPartitions
    ) {
        for (var lostPartition : lostPartitions) {
            failPendingCommitAcknowledgements(lostPartition);
            int partition = lostPartition.partition();
            activeConnectionScanStates.entrySet().removeIf(entry ->
                entry.getValue().partition().equals(lostPartition)
            );
            pendingConfirmedDead.removeIf(key ->
                key.partition() == partition && key.sourceGeneration() == lostPartition.sourceGeneration()
            );
            sourceControlQueue.removeIf(control ->
                control.evidence().partition().equals(lostPartition)
            );
            var active = partitionToActiveConnections.remove(partition);
            if (active == null) continue;
            var batch = new ArrayList<TrafficSourceReaderInterruptedClose>();
            for (var connKey : active) {
                var ts = TrafficStream.newBuilder()
                    .setNodeId(connKey.nodeId).setConnectionId(connKey.connectionId)
                    .setNumberOfThisLastChunk(0).build();
                var key = new TrafficStreamKeyWithKafkaRecordId(tsk -> {
                    var channelKeyCtx = channelContextManager.retainOrCreateContext(tsk);
                    return channelContextManager.getGlobalContext()
                        .createTrafficStreamContextForKafkaSource(channelKeyCtx, "", 0);
                }, ts, new PojoKafkaCommitOffsetData(lostPartition.sourceGeneration(), partition, -1));
                var obligationKey = new SourceConnectionPartitionGenerationKey(
                    new SourceConnectionKey(connKey.nodeId, connKey.connectionId),
                    partition,
                    lostPartition.sourceGeneration()
                );
                if (pendingSessionTerminationObligations.putIfAbsent(
                    obligationKey,
                    new SessionTerminationObligation(partition)
                ) == null) {
                    batch.add(new TrafficSourceReaderInterruptedClose(key));
                }
            }
            if (!batch.isEmpty()) {
                trafficSourceReaderInterruptedCloseQueue.add(batch);
            }
        }
    }

    private void failPendingCommitAcknowledgements(SourcePartitionKey lostPartition) {
        var cause = new SourceRunwayLostException(lostPartition);
        pendingCommitAcknowledgements.forEach((key, acknowledgement) -> {
            if (!(key instanceof KafkaCommitOffsetData kafkaKey)
                || kafkaKey.getPartition() != lostPartition.partition()
                || kafkaKey.getGeneration() != lostPartition.sourceGeneration()
                || !pendingCommitAcknowledgements.remove(key, acknowledgement)) {
                return;
            }
            acknowledgement.completeExceptionally(cause);
        });
    }

    @Override
    public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
        var acknowledgement = new CompletableFuture<Void>();
        try {
            kafkaExecutor.execute(() -> {
                var matchingObligations = pendingSessionTerminationObligations.keySet()
                    .stream()
                    .filter(key -> key.connection().equals(sessionKey.connection()))
                    .filter(key -> key.sourceGeneration() == sessionKey.sourceGeneration())
                    .toList();
                for (var obligationKey : matchingObligations) {
                    var obligation = pendingSessionTerminationObligations.remove(obligationKey);
                    if (obligation == null) {
                        continue;
                    }
                    obligation.acknowledge();
                }
                if (matchingObligations.isEmpty()) {
                    log.atTrace()
                        .setMessage("No source termination obligation was registered for {}")
                        .addArgument(sessionKey)
                        .log();
                }
                acknowledgement.complete(null);
            });
        } catch (Throwable t) {
            acknowledgement.completeExceptionally(t);
        }
        return acknowledgement.minimalCompletionStage();
    }

    private void onKeyFinishedCommitting(ITrafficStreamKey trafficStreamKey) {
        var acknowledgement = pendingCommitAcknowledgements.remove(trafficStreamKey);
        try {
            releaseRecordContext(trafficStreamKey);
            if (acknowledgement != null) {
                acknowledgement.complete(null);
            }
        } catch (Throwable t) {
            if (acknowledgement != null) {
                acknowledgement.completeExceptionally(t);
            } else {
                throw t;
            }
        }
    }

    @Override
    public void releaseTrafficStreamWithoutCommit(ITrafficStreamKey trafficStreamKey) {
        releaseRecordContext(trafficStreamKey);
    }

    private void releaseRecordContext(ITrafficStreamKey trafficStreamKey) {
        var looseParentScope = trafficStreamKey.getTrafficStreamsContext().getEnclosingScope();
        if (!(looseParentScope instanceof ReplayContexts.KafkaRecordContext)) {
            throw new IllegalArgumentException(
                "Expected parent context of type "
                    + ReplayContexts.KafkaRecordContext.class
                    + " instead of "
                    + looseParentScope
                    + " (of type="
                    + looseParentScope.getClass()
                    + ")"
            );
        }
        var kafkaCtx = (ReplayContexts.KafkaRecordContext) looseParentScope;
        kafkaCtx.close();
        channelContextManager.releaseContextFor(kafkaCtx.getImmediateEnclosingScope());
    }

    /**
     * Called by the accumulator when a connection is fully done (closed or expired).
     * Removes the connection from partitionToActiveConnections so the map doesn't grow unboundedly.
     */
    @Override
    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
        var connKey = new ScopedConnectionIdKey(trafficStreamKey.getNodeId(), trafficStreamKey.getConnectionId());
        if (trafficStreamKey instanceof KafkaCommitOffsetData) {
            var partition = ((KafkaCommitOffsetData) trafficStreamKey).getPartition();
            var set = partitionToActiveConnections.get(partition);
            if (set != null) {
                set.remove(connKey);
            }
        } else {
            partitionToActiveConnections.values().forEach(set -> set.remove(connKey));
        }
        activeConnectionScanStates.remove(connKey);
        pendingConfirmedDead.removeIf(key -> key.connection().equals(
            new SourceConnectionKey(connKey.nodeId, connKey.connectionId)
        ));
    }

    public static KafkaTrafficCaptureSource buildKafkaSource(
        @NonNull RootReplayerContext globalContext,
        @NonNull String brokers,
        @NonNull String topic,
        @NonNull String groupId,
        @NonNull String authType,
        String kafkaUserName,
        String kafkaPassword,
        String propertyFilePath,
        @NonNull Clock clock,
        @NonNull KafkaBehavioralPolicy behavioralPolicy
    ) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, authType, kafkaUserName, kafkaPassword, propertyFilePath);
        kafkaProps.putIfAbsent(MAX_POLL_INTERVAL_KEY, defaultPollIntervalMs());
        return new KafkaTrafficCaptureSource(
            globalContext,
            new KafkaConsumer<>(kafkaProps),
            topic,
            DEFAULT_KEEP_ALIVE_PERIOD,
            clock,
            behavioralPolicy
        );
    }

    public static Properties buildKafkaProperties(
        @NonNull String brokers,
        @NonNull String groupId,
        @NonNull String authType,
        String kafkaUserName,
        String kafkaPassword,
        String propertyFilePath
    ) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        kafkaProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        kafkaProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        if (propertyFilePath != null) {
            try (InputStream input = new FileInputStream(propertyFilePath)) {
                kafkaProps.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka properties file with path: {}", propertyFilePath);
                throw ex;
            }
        }
        KafkaSaslAuthHelper.applySaslAuthProperties(kafkaProps, authType, kafkaUserName, kafkaPassword);
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // Use cooperative sticky rebalancing to avoid stop-the-world partition revocation
        kafkaProps.putIfAbsent(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        return kafkaProps;
    }

    @Override
    @SneakyThrows
    public void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {
        CompletableFuture.runAsync(() -> {
            trackingKafkaConsumer.touch(context);
            runLivenessScanIfDue();
        }, kafkaExecutor).get();
    }

    /**
     * If messages are outstanding, we need to keep the connection alive, otherwise, there's no
     * reason to.  It's OK to fall out of the group and rejoin once ready.
     * @return
     */
    @Override
    public Optional<Instant> getNextRequiredTouch() {
        var kafkaTouch = trackingKafkaConsumer.getNextRequiredTouch();
        if (activeConnectionScanStates.isEmpty()) {
            return kafkaTouch;
        }
        var scanAt = Instant.ofEpochMilli(nextLivenessScanAtMillis.get());
        return kafkaTouch.map(existing -> existing.isBefore(scanAt) ? existing : scanAt)
            .or(() -> Optional.of(scanAt));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<SourceInput>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        log.atTrace().setMessage("readNextTrafficStreamChunk()").log();
        return CompletableFuture.supplyAsync(() -> {
            log.atTrace().setMessage("async...readNextTrafficStreamChunk()").log();
            return readNextTrafficStreamSynchronously(contextSupplier.get());
        }, kafkaExecutor);
    }

    public List<SourceInput> readNextTrafficStreamSynchronously(
        ITrafficSourceContexts.IReadChunkContext context
    ) {
        log.atTrace().setMessage("readNextTrafficStreamSynchronously()").log();
        // Drain synthetic closes before returning real Kafka records — one batch per revocation event
        var closeBatch = trafficSourceReaderInterruptedCloseQueue.poll();
        if (closeBatch != null) {
            log.atInfo().setMessage("Returning {} synthetic close(s) before real Kafka records")
                .addArgument(closeBatch::size).log();
            return List.copyOf(closeBatch);
        }
        var pendingControls = drainSourceControls();
        if (!pendingControls.isEmpty()) {
            return pendingControls;
        }
        // Block real data until all synthetic closes have completed their full session lifecycle.
        if (!pendingSessionTerminationObligations.isEmpty()) {
            log.atDebug().setMessage("Returning empty batch: {} source termination obligations still outstanding")
                .addArgument(pendingSessionTerminationObligations::size).log();
            // We should be draining very fast and if we block, we risk falling out of the Kafka group,
            // which could then have knock-on effects throughout the fleet since we're recovering from
            // the last recovery/partition reassignment.
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000); // yield for up to 5 ms
            return Collections.emptyList();
        }
        try {
            var sourceRecords = trackingKafkaConsumer.getNextBatchOfRecords(context, (offsetData, kafkaRecord) -> {
                try {
                    if (KafkaLivenessScanner.isLivenessRecord(kafkaRecord)) {
                        var chunk = ProxyLivenessSnapshotChunk.parseFrom(kafkaRecord.value());
                        if (chunk.getPartition() != kafkaRecord.partition()
                            || chunk.getRoutingPlanId().isBlank()) {
                            throw new IllegalStateException(
                                "Invalid liveness routing stamp at "
                                    + kafkaRecord.topic()
                                    + "-"
                                    + kafkaRecord.partition()
                                    + "@"
                                    + kafkaRecord.offset()
                            );
                        }
                        var syntheticStream = TrafficStream.newBuilder()
                            .setNodeId(chunk.getNodeId())
                            .setConnectionId(
                                "__proxy_liveness__:"
                                    + chunk.getPartition()
                                    + ":"
                                    + chunk.getSnapshotSequence()
                                    + ":"
                                    + chunk.getChunkIndex()
                            )
                            .setNumberOfThisLastChunk(0)
                            .build();
                        var key = makeKafkaRecordKey(syntheticStream, offsetData, kafkaRecord);
                        return (SourceInput) new KafkaLivenessSnapshotRecord(syntheticStream, key, chunk);
                    }
                    TrafficStream ts = TrafficStream.parseFrom(kafkaRecord.value());
                    if (ts.hasPartition() != ts.hasRoutingPlanId()) {
                        throw new IllegalStateException(
                            "Traffic record has only part of its routing stamp at "
                                + kafkaRecord.topic()
                                + "-"
                                + kafkaRecord.partition()
                                + "@"
                                + kafkaRecord.offset()
                        );
                    }
                    if (ts.hasPartition()) {
                        KafkaLivenessScanner.validateTrafficStamp(kafkaRecord, ts);
                    }
                    var trafficStreamsSoFar = trafficStreamsRead.incrementAndGet();
                    log.atTrace().setMessage("Parsed traffic stream #{}: {} {}")
                        .addArgument(trafficStreamsSoFar)
                        .addArgument(offsetData)
                        .addArgument(ts)
                        .log();
                    var key = makeKafkaRecordKey(ts, offsetData, kafkaRecord);
                    // Track active connections per partition for synthetic close injection
                    var connKey = new ScopedConnectionIdKey(
                        ts.getNodeId(), ts.getConnectionId());
                    var activeSet = partitionToActiveConnections
                        .computeIfAbsent(offsetData.getPartition(),
                            p -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
                    boolean isNewConnection = activeSet.add(connKey);
                    if (ts.hasPartition()) {
                        activeConnectionScanStates.compute(connKey, (ignored, previous) -> {
                            var partition = new SourcePartitionKey(
                                trackingKafkaConsumer.topic,
                                offsetData.getPartition(),
                                offsetData.getGeneration()
                            );
                            if (previous != null
                                && previous.partition().equals(partition)
                                && !previous.routingPlanId().equals(ts.getRoutingPlanId())) {
                                throw new IllegalStateException(
                                    "Routing plan changed within connection " + connKey
                                );
                            }
                            var requirement = previous == null
                                ? FollowUpRequirement.CONNECTION_TERMINATION
                                : previous.requirement();
                            return new ActiveConnectionScanState(
                                partition,
                                ts.getRoutingPlanId(),
                                offsetData.getOffset(),
                                requirement
                            );
                        });
                    }
                    // Handoff: first time we see this connection on this partition AND no READ observation
                    // (another replayer was mid-connection). Continuation streams for known connections are not resumeds.
                    boolean startsWithRead = ts.getSubStreamList().stream()
                        .findFirst()
                        .map(TrafficObservation::hasRead)
                        .orElse(false);
                    final boolean resumed = isNewConnection && !startsWithRead;
                    return (SourceInput) new PojoTrafficStreamAndKey(ts, key) {
                        @Override
                        public boolean isResumedConnection() { return resumed; }
                    };
                } catch (InvalidProtocolBufferException e) {
                    // Assume the behavioralPolicy instance does any logging that the host may be interested in
                    RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(kafkaRecord, e);
                    if (recordError != null) {
                        throw recordError;
                    } else {
                        return (SourceInput) null;
                    }
                }
            }).filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList<SourceInput>::new));
            runLivenessScanIfDue();
            sourceRecords.addAll(drainSourceControls());
            return List.copyOf(sourceRecords);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Terminating Kafka traffic stream due to exception").log();
            throw e;
        }
    }

    private TrafficStreamKeyWithKafkaRecordId makeKafkaRecordKey(
        TrafficStream stream,
        KafkaCommitOffsetData offsetData,
        org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> kafkaRecord
    ) {
        return new TrafficStreamKeyWithKafkaRecordId(tsk -> {
            var channelKeyCtx = channelContextManager.retainOrCreateContext(tsk);
            return channelContextManager.getGlobalContext()
                .createTrafficStreamContextForKafkaSource(
                    channelKeyCtx,
                    kafkaRecord.key(),
                    kafkaRecord.serializedKeySize() + kafkaRecord.serializedValueSize()
                );
        }, stream, offsetData);
    }

    private void runLivenessScanIfDue() {
        if (activeConnectionScanStates.isEmpty()) {
            return;
        }
        long now = clock.millis();
        long next = nextLivenessScanAtMillis.get();
        if (now < next || !nextLivenessScanAtMillis.compareAndSet(next, now + LIVENESS_SCAN_INTERVAL.toMillis())) {
            return;
        }
        var candidates = activeConnectionScanStates.entrySet()
            .stream()
            .map(entry -> new KafkaLivenessScanner.Candidate(
                entry.getValue().partition(),
                new SourceConnectionKey(entry.getKey().nodeId, entry.getKey().connectionId),
                entry.getValue().routingPlanId(),
                entry.getValue().lastReplayedOffset(),
                entry.getValue().requirement()
            ))
            .toList();
        var cycle = trackingKafkaConsumer.scanAhead(MAX_LIVENESS_SCAN_RECORDS, LIVENESS_SCAN_BUDGET);
        for (var evidence : livenessScanner.evaluate(candidates, cycle)) {
            if (!(evidence instanceof ScanEvidence.ConfirmedAbsent confirmedAbsent)) {
                continue;
            }
            var pendingKey = new SourceConnectionPartitionGenerationKey(
                confirmedAbsent.connection(),
                confirmedAbsent.partition().partition(),
                confirmedAbsent.partition().sourceGeneration()
            );
            if (pendingConfirmedDead.add(pendingKey)) {
                sourceControlQueue.add(new SourceControlEvent.ConfirmedDead(confirmedAbsent));
                log.atInfo()
                    .setMessage("Proxy liveness proved {} dead with {}")
                    .addArgument(confirmedAbsent.connection())
                    .addArgument(confirmedAbsent.proof())
                    .log();
            }
        }
    }

    private List<SourceInput> drainSourceControls() {
        var controls = new ArrayList<SourceInput>();
        SourceControlEvent.ConfirmedDead control;
        while ((control = sourceControlQueue.poll()) != null) {
            controls.add(control);
        }
        return List.copyOf(controls);
    }

    @Override
    public void updateScanBlocker(
        ITrafficStreamKey trafficStreamKey,
        FollowUpRequirement followUpRequirement
    ) {
        if (!(trafficStreamKey instanceof KafkaCommitOffsetData kafkaKey)) {
            return;
        }
        var connection = new ScopedConnectionIdKey(
            trafficStreamKey.getNodeId(),
            trafficStreamKey.getConnectionId()
        );
        activeConnectionScanStates.computeIfPresent(connection, (ignored, existing) ->
            existing.partition().partition() == kafkaKey.getPartition()
                && existing.partition().sourceGeneration() == kafkaKey.getGeneration()
                    ? existing.withRequirement(followUpRequirement)
                    : existing
        );
    }

    @Override
    public boolean usesStructuralExpiration() {
        return true;
    }

    @Override
    public boolean hasPendingSourceControl() {
        return !sourceControlQueue.isEmpty() || !trafficSourceReaderInterruptedCloseQueue.isEmpty();
    }

    @Override
    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        if (!(trafficStreamKey instanceof TrafficStreamKeyWithKafkaRecordId)) {
            throw new IllegalArgumentException(
                "Expected key of type "
                    + TrafficStreamKeyWithKafkaRecordId.class
                    + " but received "
                    + trafficStreamKey
                    + " (of type="
                    + trafficStreamKey.getClass()
                    + ")"
            );
        }
        return trackingKafkaConsumer.commitKafkaKey(
            trafficStreamKey,
            (TrafficStreamKeyWithKafkaRecordId) trafficStreamKey
        );
    }

    @Override
    public CompletableFuture<Void> commitTrafficStreamAsync(ITrafficStreamKey trafficStreamKey) {
        var acknowledgement = new CompletableFuture<Void>();
        var previous = pendingCommitAcknowledgements.putIfAbsent(trafficStreamKey, acknowledgement);
        if (previous != null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("commit acknowledgement already pending for " + trafficStreamKey)
            );
        }
        try {
            var result = commitTrafficStream(trafficStreamKey);
            if (result == CommitResult.IGNORED) {
                pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
                acknowledgement.completeExceptionally(
                    new SourceRunwayLostException(sourcePartitionFor(trafficStreamKey))
                );
            } else if (result == CommitResult.IMMEDIATE) {
                pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
                acknowledgement.complete(null);
            }
        } catch (Throwable t) {
            pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
            acknowledgement.completeExceptionally(t);
        }
        return acknowledgement;
    }

    @Override
    public RecordId recordIdFor(ITrafficStreamKey trafficStreamKey) {
        if (!(trafficStreamKey instanceof KafkaCommitOffsetData kafkaRecord)) {
            return ISimpleTrafficCaptureSource.super.recordIdFor(trafficStreamKey);
        }
        if (kafkaRecord.getOffset() < 0) {
            return new SourceControlRecordId(
                new SourceConnectionKey(trafficStreamKey.getNodeId(), trafficStreamKey.getConnectionId()),
                "source-reader-interrupted-close",
                kafkaRecord.getGeneration()
            );
        }
        return new KafkaRecordId(
            trackingKafkaConsumer.topic,
            kafkaRecord.getPartition(),
            kafkaRecord.getOffset(),
            kafkaRecord.getGeneration()
        );
    }

    @Override
    public SourcePartitionKey sourcePartitionFor(ITrafficStreamKey trafficStreamKey) {
        if (!(trafficStreamKey instanceof KafkaCommitOffsetData kafkaRecord)) {
            return ISimpleTrafficCaptureSource.super.sourcePartitionFor(trafficStreamKey);
        }
        return new SourcePartitionKey(
            trackingKafkaConsumer.topic,
            kafkaRecord.getPartition(),
            kafkaRecord.getGeneration()
        );
    }

    @Override
    public void setSourcePartitionLifecycleListener(SourcePartitionLifecycleListener listener) {
        trackingKafkaConsumer.setSourcePartitionLifecycleListener(listener);
    }

    /**
     * Log a periodic heartbeat summarizing the Kafka consumer state.
     * Safe to call from any thread — uses only atomic reads and synchronized blocks.
     */
    @Override
    public void logHeartbeat() {
        trackingKafkaConsumer.logHeartbeat();
    }

    @Override
    public void close() throws IOException, InterruptedException, ExecutionException {
        if (isClosed.compareAndSet(false, true)) {
            try {
                kafkaExecutor.submit(trackingKafkaConsumer::close).get();
            } finally {
                var cause = new CancellationException("Kafka traffic source closed before commit acknowledgement");
                pendingCommitAcknowledgements.forEach((key, acknowledgement) ->
                    acknowledgement.completeExceptionally(cause)
                );
                pendingCommitAcknowledgements.clear();
                pendingSessionTerminationObligations.forEach((key, obligation) -> obligation.fail(cause));
                pendingSessionTerminationObligations.clear();
                kafkaExecutor.shutdownNow();
            }
        }
    }
}
