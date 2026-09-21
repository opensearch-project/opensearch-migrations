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
import java.util.Map;
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
import org.opensearch.migrations.replay.lifecycle.SourceCommitNotAcceptedException;
import org.opensearch.migrations.replay.lifecycle.SourceCommitUnknownAfterRevocationException;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.lifecycle.UnconfiguredSourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.SourceInput;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
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
    public record CaptureRecordCounters(long traffic, long heartbeats, long capabilityProbes) {}

    record SessionTerminationStateSnapshot(
        int pendingSessionTerminations,
        int retiringSourcePartitions,
        int queuedSyntheticCloseBatches,
        Map<SourcePartitionKey, Long> pendingTerminationsByGeneration
    ) {}

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
    private final AtomicLong heartbeatRecordsRead;
    private final AtomicLong capabilityProbeRecordsRead;
    private final ChannelContextManager channelContextManager;
    private final AtomicBoolean isClosed;
    private final ConcurrentHashMap<ITrafficStreamKey, CompletableFuture<Void>> pendingCommitAcknowledgements =
        new ConcurrentHashMap<>();
    /** Active connections per Kafka partition. Entries removed when connections are closed */
    final ConcurrentHashMap<Integer, Set<ScopedConnectionIdKey>> partitionToActiveConnections =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScopedConnectionIdKey, SourcePartitionKey> activeConnectionSourcePartitions =
        new ConcurrentHashMap<>();
    private final Set<SourcePartitionKey> retiringSourcePartitions = ConcurrentHashMap.newKeySet();
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
    private volatile SourcePartitionLifecycleListener sourcePartitionLifecycleListener =
        new UnconfiguredSourcePartitionLifecycleListener();

    public KafkaTrafficCaptureSource(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        String topic,
        Duration keepAliveInterval
    ) {
        this(globalContext, kafkaConsumer, topic, keepAliveInterval, Clock.systemUTC());
    }

    public KafkaTrafficCaptureSource(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        @NonNull String topic,
        Duration keepAliveInterval,
        Clock clock
    ) {
        this(
            globalContext,
            kafkaConsumer,
            topic,
            keepAliveInterval,
            clock,
            TrackingKafkaConsumer.UNBOUNDED_OWNED_RECORDS,
            TrackingKafkaConsumer.UNBOUNDED_OWNED_BYTES
        );
    }

    public KafkaTrafficCaptureSource(
        @NonNull RootReplayerContext globalContext,
        Consumer<String, byte[]> kafkaConsumer,
        @NonNull String topic,
        Duration keepAliveInterval,
        Clock clock,
        int maximumOwnedRecords,
        long maximumOwnedBytes
    ) {
        this.channelContextManager = new ChannelContextManager(globalContext);
        trackingKafkaConsumer = new TrackingKafkaConsumer(
            globalContext,
            kafkaConsumer,
            topic,
            keepAliveInterval,
            clock,
            this::onKeyFinishedCommitting,
            globalContext.getKafkaCommitStateMetrics(),
            maximumOwnedRecords,
            maximumOwnedBytes
        );
        trafficStreamsRead = new AtomicLong();
        heartbeatRecordsRead = new AtomicLong();
        capabilityProbeRecordsRead = new AtomicLong();
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
            retiringSourcePartitions.add(lostPartition);
            failPendingCommitAcknowledgements(lostPartition);
            int partition = lostPartition.partition();
            var active = partitionToActiveConnections.get(partition);
            if (active == null) {
                retireSourcePartitionIfDrained(lostPartition);
                continue;
            }
            var lostConnections = new ArrayList<ScopedConnectionIdKey>();
            for (var connKey : active) {
                if (activeConnectionSourcePartitions.remove(connKey, lostPartition)) {
                    active.remove(connKey);
                    lostConnections.add(connKey);
                }
            }
            if (active.isEmpty()) {
                partitionToActiveConnections.remove(partition, active);
            }
            var batch = new ArrayList<TrafficSourceReaderInterruptedClose>();
            for (var connKey : lostConnections) {
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
            retireSourcePartitionIfDrained(lostPartition);
        }
    }

    private void failPendingCommitAcknowledgements(SourcePartitionKey lostPartition) {
        var cause = new SourceCommitUnknownAfterRevocationException(lostPartition);
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
        log.atDebug()
            .setMessage("Queueing source termination acknowledgement for {}; outstanding={}")
            .addArgument(sessionKey)
            .addArgument(pendingSessionTerminationObligations::size)
            .log();
        try {
            kafkaExecutor.execute(() -> {
                var matchingObligations = pendingSessionTerminationObligations.keySet()
                    .stream()
                    .filter(key -> key.connection().equals(sessionKey.connection()))
                    .filter(key -> key.sourceGeneration() == sessionKey.sourceGeneration())
                    .toList();
                log.atDebug()
                    .setMessage("Applying source termination acknowledgement for {}; matching={}; outstanding={}")
                    .addArgument(sessionKey)
                    .addArgument(matchingObligations::size)
                    .addArgument(pendingSessionTerminationObligations::size)
                    .log();
                for (var obligationKey : matchingObligations) {
                    var obligation = pendingSessionTerminationObligations.remove(obligationKey);
                    if (obligation == null) {
                        continue;
                    }
                    obligation.acknowledge();
                    log.atDebug()
                        .setMessage("Settled source termination obligation for {} on partition {}; outstanding={}")
                        .addArgument(sessionKey)
                        .addArgument(obligation::partition)
                        .addArgument(pendingSessionTerminationObligations::size)
                        .log();
                }
                matchingObligations.stream()
                    .map(obligation -> new SourcePartitionKey(
                        trackingKafkaConsumer.topic,
                        obligation.partition(),
                        obligation.sourceGeneration()
                    ))
                    .distinct()
                    .forEach(this::retireSourcePartitionIfDrained);
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

    private void retireSourcePartitionIfDrained(SourcePartitionKey partition) {
        var hasOutstandingTermination = pendingSessionTerminationObligations.keySet()
            .stream()
            .anyMatch(obligation ->
                obligation.partition() == partition.partition()
                    && obligation.sourceGeneration() == partition.sourceGeneration()
            );
        if (hasOutstandingTermination) {
            log.atDebug()
                .setMessage("Source generation retirement is blocked by session termination; partition={}; state={}")
                .addArgument(partition)
                .addArgument(this::sessionTerminationStateSnapshot)
                .log();
            return;
        }
        if (!retiringSourcePartitions.remove(partition)) {
            return;
        }
        log.atInfo()
            .setMessage("Source generation {} drained after synthetic session termination")
            .addArgument(partition)
            .log();
        sourcePartitionLifecycleListener.onRetired(List.of(partition));
    }

    SessionTerminationStateSnapshot sessionTerminationStateSnapshot() {
        var pendingByGeneration = pendingSessionTerminationObligations.keySet()
            .stream()
            .collect(Collectors.groupingBy(
                obligation -> new SourcePartitionKey(
                    trackingKafkaConsumer.topic,
                    obligation.partition(),
                    obligation.sourceGeneration()
                ),
                Collectors.counting()
            ));
        return new SessionTerminationStateSnapshot(
            pendingSessionTerminationObligations.size(),
            retiringSourcePartitions.size(),
            trafficSourceReaderInterruptedCloseQueue.size(),
            Map.copyOf(pendingByGeneration)
        );
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
        var connection = new SourceConnectionKey(connKey.nodeId, connKey.connectionId);
        if (trafficStreamKey instanceof KafkaCommitOffsetData kafkaKey) {
            var completedPartition = new SourcePartitionKey(
                trackingKafkaConsumer.topic,
                kafkaKey.getPartition(),
                kafkaKey.getGeneration()
            );
            var activePartition = activeConnectionSourcePartitions.get(connKey);
            if (activePartition != null && !activePartition.equals(completedPartition)) {
                log.atDebug()
                    .setMessage("Ignoring stale accumulation completion for {} from {}; current source partition is {}")
                    .addArgument(connection)
                    .addArgument(completedPartition)
                    .addArgument(activePartition)
                    .log();
                return;
            }
            activeConnectionSourcePartitions.remove(connKey, completedPartition);
            var partition = kafkaKey.getPartition();
            var set = partitionToActiveConnections.get(partition);
            if (set != null) {
                set.remove(connKey);
                if (set.isEmpty()) {
                    partitionToActiveConnections.remove(partition, set);
                }
            }
        } else {
            partitionToActiveConnections.values().forEach(set -> set.remove(connKey));
            activeConnectionSourcePartitions.remove(connKey);
        }
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
        int maximumOwnedRecords,
        long maximumOwnedBytes,
        boolean ignoredLivenessScanAheadEnabled
    ) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, authType, kafkaUserName, kafkaPassword, propertyFilePath);
        kafkaProps.putIfAbsent(MAX_POLL_INTERVAL_KEY, defaultPollIntervalMs());
        return new KafkaTrafficCaptureSource(
            globalContext,
            new KafkaConsumer<>(kafkaProps),
            topic,
            DEFAULT_KEEP_ALIVE_PERIOD,
            clock,
            maximumOwnedRecords,
            maximumOwnedBytes
        );
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
        int maximumOwnedRecords,
        long maximumOwnedBytes
    ) throws IOException {
        return buildKafkaSource(
            globalContext,
            brokers,
            topic,
            groupId,
            authType,
            kafkaUserName,
            kafkaPassword,
            propertyFilePath,
            clock,
            maximumOwnedRecords,
            maximumOwnedBytes,
            true
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
        CompletableFuture.runAsync(() -> trackingKafkaConsumer.touch(context), kafkaExecutor).get();
    }

    /**
     * If messages are outstanding, we need to keep the connection alive, otherwise, there's no
     * reason to.  It's OK to fall out of the group and rejoin once ready.
     * @return
     */
    @Override
    public Optional<Instant> getNextRequiredTouch() {
        return trackingKafkaConsumer.getNextRequiredTouch();
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
        if (!trackingKafkaConsumer.isReadCapacityAvailable()) {
            return Collections.emptyList();
        }
        try {
            var sourceRecords = trackingKafkaConsumer.getNextBatchOfRecords(context, this::decodeKafkaRecord)
                .collect(Collectors.toCollection(ArrayList<SourceInput>::new));
            return List.copyOf(sourceRecords);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Terminating Kafka traffic stream due to exception").log();
            throw e;
        }
    }

    private SourceInput decodeKafkaRecord(
        KafkaCommitOffsetData offsetData,
        org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> kafkaRecord
    ) {
        final CaptureRecord captureRecord;
        try {
            captureRecord = CaptureRecord.parseFrom(kafkaRecord.value());
        } catch (InvalidProtocolBufferException e) {
            throw protocolViolation(kafkaRecord, "value is not a CaptureRecord envelope", e);
        }

        return switch (captureRecord.getPayloadCase()) {
            case TRAFFICSTREAM -> decodeTrafficStream(
                captureRecord.getTrafficStream(),
                offsetData,
                kafkaRecord
            );
            case WRITERPARTITIONHEARTBEAT -> {
                heartbeatRecordsRead.incrementAndGet();
                yield makeControlRecord(
                    captureRecord,
                    captureRecord.getWriterPartitionHeartbeat().getWriterNodeId(),
                    "heartbeat",
                    offsetData,
                    kafkaRecord
                );
            }
            case CAPTURECAPABILITYPROBE -> {
                capabilityProbeRecordsRead.incrementAndGet();
                yield makeControlRecord(
                    captureRecord,
                    captureRecord.getCaptureCapabilityProbe().getWriterNodeId(),
                    "probe:" + captureRecord.getCaptureCapabilityProbe().getProbeId(),
                    offsetData,
                    kafkaRecord
                );
            }
            case PAYLOAD_NOT_SET ->
                throw protocolViolation(kafkaRecord, "CaptureRecord.payload is not set", null);
        };
    }

    private SourceInput decodeTrafficStream(
        TrafficStream trafficStream,
        KafkaCommitOffsetData offsetData,
        org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> kafkaRecord
    ) {
        var trafficStreamsSoFar = trafficStreamsRead.incrementAndGet();
        log.atTrace().setMessage("Parsed traffic stream #{}: {} {}")
            .addArgument(trafficStreamsSoFar)
            .addArgument(offsetData)
            .addArgument(trafficStream)
            .log();
        var key = makeKafkaRecordKey(trafficStream, offsetData, kafkaRecord);
        var connection = new ScopedConnectionIdKey(
            trafficStream.getNodeId(),
            trafficStream.getConnectionId()
        );
        var sourcePartition = new SourcePartitionKey(
            trackingKafkaConsumer.topic,
            offsetData.getPartition(),
            offsetData.getGeneration()
        );
        var previousSourcePartition = activeConnectionSourcePartitions.put(connection, sourcePartition);
        if (previousSourcePartition != null
            && previousSourcePartition.partition() != sourcePartition.partition()) {
            var previousActiveSet = partitionToActiveConnections.get(previousSourcePartition.partition());
            if (previousActiveSet != null) {
                previousActiveSet.remove(connection);
                if (previousActiveSet.isEmpty()) {
                    partitionToActiveConnections.remove(
                        previousSourcePartition.partition(),
                        previousActiveSet
                    );
                }
            }
        }
        partitionToActiveConnections
            .computeIfAbsent(
                offsetData.getPartition(),
                ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>())
            )
            .add(connection);
        boolean startsWithRead = trafficStream.getSubStreamList().stream()
            .findFirst()
            .map(TrafficObservation::hasRead)
            .orElse(false);
        boolean isNewConnection = previousSourcePartition == null
            || !previousSourcePartition.equals(sourcePartition);
        final boolean resumed = isNewConnection && !startsWithRead;
        return new PojoTrafficStreamAndKey(trafficStream, key) {
            @Override
            public boolean isResumedConnection() {
                return resumed;
            }
        };
    }

    private KafkaCaptureControlRecord makeControlRecord(
        CaptureRecord captureRecord,
        String writerNodeId,
        String controlId,
        KafkaCommitOffsetData offsetData,
        org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> kafkaRecord
    ) {
        var syntheticStream = TrafficStream.newBuilder()
            .setNodeId(writerNodeId)
            .setConnectionId(
                "__capture_control__:"
                    + controlId
                    + ":"
                    + kafkaRecord.partition()
                    + ":"
                    + kafkaRecord.offset()
            )
            .setNumberOfThisLastChunk(0)
            .build();
        return new KafkaCaptureControlRecord(
            captureRecord,
            syntheticStream,
            makeKafkaRecordKey(syntheticStream, offsetData, kafkaRecord)
        );
    }

    private static CaptureRecordProtocolViolationException protocolViolation(
        org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> kafkaRecord,
        String reason,
        Throwable cause
    ) {
        var message = "Capture protocol violation at "
            + kafkaRecord.topic()
            + "-"
            + kafkaRecord.partition()
            + "@"
            + kafkaRecord.offset()
            + ": "
            + reason;
        return cause == null
            ? new CaptureRecordProtocolViolationException(message)
            : new CaptureRecordProtocolViolationException(message, cause);
    }

    public CaptureRecordCounters captureRecordCounters() {
        return new CaptureRecordCounters(
            trafficStreamsRead.get(),
            heartbeatRecordsRead.get(),
            capabilityProbeRecordsRead.get()
        );
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

    @Override
    public boolean usesStructuralExpiration() {
        return true;
    }

    @Override
    public boolean hasPendingSourceControl() {
        return !trafficSourceReaderInterruptedCloseQueue.isEmpty();
    }

    @Override
    public boolean isReadCapacityAvailable() {
        return trackingKafkaConsumer.isReadCapacityAvailable();
    }

    @Override
    public void setReadCapacityAvailableListener(Runnable listener) {
        trackingKafkaConsumer.setReadCapacityAvailableListener(listener);
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
        try {
            kafkaExecutor.execute(() -> acceptCommitOnSourceOwnerThread(trafficStreamKey, acknowledgement));
        } catch (Throwable t) {
            acknowledgement.completeExceptionally(t);
        }
        return acknowledgement;
    }

    private void acceptCommitOnSourceOwnerThread(
        ITrafficStreamKey trafficStreamKey,
        CompletableFuture<Void> acknowledgement
    ) {
        if (isClosed.get()) {
            acknowledgement.completeExceptionally(
                new CancellationException("Kafka traffic source closed before commit acceptance")
            );
            return;
        }
        try {
            var previous = pendingCommitAcknowledgements.putIfAbsent(
                trafficStreamKey,
                acknowledgement
            );
            if (previous != null) {
                throw new IllegalStateException(
                    "commit acknowledgement already pending for " + trafficStreamKey
                );
            }
            CommitResult result;
            try {
                result = commitTrafficStream(trafficStreamKey);
                if (result == CommitResult.IGNORED || result == CommitResult.IMMEDIATE) {
                    pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
                }
            } catch (Throwable t) {
                pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
                throw t;
            }
            if (result == CommitResult.IGNORED) {
                acknowledgement.completeExceptionally(
                    new SourceCommitNotAcceptedException(sourcePartitionFor(trafficStreamKey))
                );
                return;
            }
            if (result == CommitResult.IMMEDIATE) {
                acknowledgement.complete(null);
                return;
            }
            // AFTER_NEXT_READ / BLOCKED_BY_OTHER_COMMITS: intake may end before another poll-driven
            // flush. The source owner performs the flush directly after accepting the commit.
            trackingKafkaConsumer.commitStagedOffsets();
        } catch (Throwable t) {
            pendingCommitAcknowledgements.remove(trafficStreamKey, acknowledgement);
            acknowledgement.completeExceptionally(t);
        }
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
        sourcePartitionLifecycleListener = Objects.requireNonNull(listener);
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
                kafkaExecutor.submit(() -> {
                    try {
                        // §16.2: flush eligible staged commits before closing the consumer, so a
                        // clean shutdown doesn't discard commits the replay already earned.
                        trackingKafkaConsumer.commitStagedOffsets();
                    } catch (RuntimeException e) {
                        log.atWarn().setCause(e)
                            .setMessage("Final staged-commit flush failed; pending acknowledgements will fail")
                            .log();
                    }
                    trackingKafkaConsumer.close();
                }).get();
            } finally {
                var cause = new CancellationException("Kafka traffic source closed before commit acknowledgement");
                pendingCommitAcknowledgements.forEach((key, acknowledgement) ->
                    acknowledgement.completeExceptionally(cause)
                );
                pendingCommitAcknowledgements.clear();
                pendingSessionTerminationObligations.forEach((key, obligation) -> obligation.fail(cause));
                pendingSessionTerminationObligations.clear();
                retiringSourcePartitions.clear();
                kafkaExecutor.shutdownNow();
            }
        }
    }
}
