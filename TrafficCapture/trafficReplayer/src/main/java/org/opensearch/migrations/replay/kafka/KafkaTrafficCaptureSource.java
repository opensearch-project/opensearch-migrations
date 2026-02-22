package org.opensearch.migrations.replay.kafka;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
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
    public static final String MAX_POLL_INTERVAL_KEY = "max.poll.interval.ms";
    // see
    // https://stackoverflow.com/questions/39730126/difference-between-session-timeout-ms-and-max-poll-interval-ms-for-kafka-0-10
    public static final String DEFAULT_POLL_INTERVAL_MS = "60000";

    final TrackingKafkaConsumer trackingKafkaConsumer;
    private final ExecutorService kafkaExecutor;
    private final AtomicLong trafficStreamsRead;
    private final KafkaBehavioralPolicy behavioralPolicy;
    private final ChannelContextManager channelContextManager;
    private final AtomicBoolean isClosed;
    /** Active connections per Kafka partition. Populated as records are consumed; cleared on synthetic close flush. */
    final java.util.concurrent.ConcurrentHashMap<Integer, java.util.Set<org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey>>
        partitionToActiveConnections = new java.util.concurrent.ConcurrentHashMap<>();
    /** Synthetic close events to drain before returning real Kafka records. */
    private final java.util.Queue<SyntheticPartitionReassignmentClose> syntheticCloseQueue =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** How long to delay the first request on a handoff connection (configurable). */
    private final java.time.Duration quiescentDuration;

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
        this.quiescentDuration = java.time.Duration.ofSeconds(5); // default; TODO: make configurable
        kafkaConsumer.subscribe(Collections.singleton(topic), trackingKafkaConsumer);
        kafkaExecutor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("kafkaConsumerThread"));
        isClosed = new AtomicBoolean(false);
        // Register callback: when partitions are truly lost, enqueue synthetic closes for their active connections
        trackingKafkaConsumer.setOnPartitionsTrulyLostCallback(this::enqueueSyntheticClosesForPartitions);
    }

    private void enqueueSyntheticClosesForPartitions(java.util.Collection<Integer> lostPartitions) {
        for (int partition : lostPartitions) {
            var active = partitionToActiveConnections.remove(partition);
            if (active == null) continue;
            for (var connKey : active) {
                var ts = org.opensearch.migrations.trafficcapture.protos.TrafficStream.newBuilder()
                    .setNodeId(connKey.nodeId).setConnectionId(connKey.connectionId)
                    .setNumberOfThisLastChunk(0).build();
                var key = new TrafficStreamKeyWithKafkaRecordId(tsk -> {
                    var channelKeyCtx = channelContextManager.retainOrCreateContext(tsk);
                    return channelContextManager.getGlobalContext()
                        .createTrafficStreamContextForKafkaSource(channelKeyCtx, "", 0);
                }, ts, new PojoKafkaCommitOffsetData(trackingKafkaConsumer.getConsumerConnectionGeneration(), partition, -1));
                syntheticCloseQueue.add(new SyntheticPartitionReassignmentClose(key));
            }
        }
    }

    private void onKeyFinishedCommitting(ITrafficStreamKey trafficStreamKey) {
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
    public void onConnectionDone(ITrafficStreamKey trafficStreamKey) {
        var connKey = new org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey(
            trafficStreamKey.getNodeId(), trafficStreamKey.getConnectionId());
        partitionToActiveConnections.values().forEach(set -> set.remove(connKey));
    }

    public static KafkaTrafficCaptureSource buildKafkaSource(
        @NonNull RootReplayerContext globalContext,
        @NonNull String brokers,
        @NonNull String topic,
        @NonNull String groupId,
        boolean enableMSKAuth,
        String propertyFilePath,
        @NonNull Clock clock,
        @NonNull KafkaBehavioralPolicy behavioralPolicy
    ) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        kafkaProps.putIfAbsent(MAX_POLL_INTERVAL_KEY, DEFAULT_POLL_INTERVAL_MS);
        var pollPeriod = Duration.ofMillis(Long.valueOf((String) kafkaProps.get(MAX_POLL_INTERVAL_KEY)));
        var keepAlivePeriod = getKeepAlivePeriodFromPollPeriod(pollPeriod);
        return new KafkaTrafficCaptureSource(
            globalContext,
            new KafkaConsumer<>(kafkaProps),
            topic,
            keepAlivePeriod,
            clock,
            behavioralPolicy
        );
    }

    /**
     * We'll have to 'maintain' touches more frequently than the poll period, otherwise the
     * consumer will fall out of the group, putting all the commits in-flight at risk.  Notice
     * that this doesn't have a bearing on heartbeats, which themselves are maintained through
     * Kafka Consumer poll() calls.  When those poll calls stop, so does the heartbeat, which
     * is more sensitive, but managed via the 'session.timeout.ms' property.
     */
    private static Duration getKeepAlivePeriodFromPollPeriod(Duration pollPeriod) {
        return pollPeriod.dividedBy(2);
    }

    public static Properties buildKafkaProperties(
        @NonNull String brokers,
        @NonNull String groupId,
        boolean enableMSKAuth,
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
        // Required for using SASL auth with MSK public endpoint
        if (enableMSKAuth) {
            kafkaProps.setProperty("security.protocol", "SASL_SSL");
            kafkaProps.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            kafkaProps.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            kafkaProps.setProperty(
                "sasl.client.callback.handler.class",
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
            );
        }
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
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
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        log.atTrace().setMessage("readNextTrafficStreamChunk()").log();
        return CompletableFuture.supplyAsync(() -> {
            log.atTrace().setMessage("async...readNextTrafficStreamChunk()").log();
            return readNextTrafficStreamSynchronously(contextSupplier.get());
        }, kafkaExecutor);
    }

    public List<ITrafficStreamWithKey> readNextTrafficStreamSynchronously(
        ITrafficSourceContexts.IReadChunkContext context
    ) {
        log.atTrace().setMessage("readNextTrafficStreamSynchronously()").log();
        // Drain synthetic closes before returning real Kafka records
        if (!syntheticCloseQueue.isEmpty()) {
            var closes = new java.util.ArrayList<ITrafficStreamWithKey>();
            SyntheticPartitionReassignmentClose close;
            while ((close = syntheticCloseQueue.poll()) != null) {
                closes.add(close);
            }
            log.atInfo().setMessage("Returning {} synthetic close(s) before real Kafka records").addArgument(closes::size).log();
            return closes;
        }
        try {
            return trackingKafkaConsumer.getNextBatchOfRecords(context, (offsetData, kafkaRecord) -> {
                try {
                    TrafficStream ts = TrafficStream.parseFrom(kafkaRecord.value());
                    var trafficStreamsSoFar = trafficStreamsRead.incrementAndGet();
                    log.atTrace().setMessage("Parsed traffic stream #{}: {} {}")
                        .addArgument(trafficStreamsSoFar)
                        .addArgument(offsetData)
                        .addArgument(ts)
                        .log();
                    var key = new TrafficStreamKeyWithKafkaRecordId(tsk -> {
                        var channelKeyCtx = channelContextManager.retainOrCreateContext(tsk);
                        return channelContextManager.getGlobalContext()
                            .createTrafficStreamContextForKafkaSource(
                                channelKeyCtx,
                                kafkaRecord.key(),
                                kafkaRecord.serializedKeySize() + kafkaRecord.serializedValueSize()
                            );
                    }, ts, offsetData);
                    // Track active connections per partition for synthetic close injection
                    var connKey = new org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey(
                        ts.getNodeId(), ts.getConnectionId());
                    var activeSet = partitionToActiveConnections
                        .computeIfAbsent(offsetData.getPartition(),
                            p -> java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
                    boolean isNewConnection = activeSet.add(connKey);
                    // Handoff: first time we see this connection on this partition AND no READ observation
                    // (another replayer was mid-connection). Continuation streams for known connections are not handoffs.
                    boolean startsWithRead = ts.getSubStreamList().stream()
                        .findFirst()
                        .map(org.opensearch.migrations.trafficcapture.protos.TrafficObservation::hasRead)
                        .orElse(false);
                    final java.time.Instant quiescentUntil = (isNewConnection && !startsWithRead)
                        ? java.time.Instant.now().plus(quiescentDuration) : null;
                    return (ITrafficStreamWithKey) new PojoTrafficStreamAndKey(ts, key) {
                        @Override
                        public java.time.Instant getQuiescentUntil() { return quiescentUntil; }
                    };
                } catch (InvalidProtocolBufferException e) {
                    // Assume the behavioralPolicy instance does any logging that the host may be interested in
                    RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(kafkaRecord, e);
                    if (recordError != null) {
                        throw recordError;
                    } else {
                        return null;
                    }
                }
            }).filter(Objects::nonNull).collect(Collectors.<ITrafficStreamWithKey>toList());
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Terminating Kafka traffic stream due to exception").log();
            throw e;
        }
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
    public void close() throws IOException, InterruptedException, ExecutionException {
        if (isClosed.compareAndSet(false, true)) {
            kafkaExecutor.submit(trackingKafkaConsumer::close).get();
            kafkaExecutor.shutdownNow();
        }
    }
}
