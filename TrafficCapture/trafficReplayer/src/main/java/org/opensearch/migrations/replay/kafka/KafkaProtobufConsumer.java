package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Adapt a Kafka stream into a TrafficCaptureSource.
 *
 * Notice that there's a critical gap between how Kafka accepts commits and how the
 * BlockingTrafficSource throttles calls to Kafka.  The BlockingTrafficSource may
 * block calls to readNextTrafficStreamChunk() until some time window elapses.  This
 * could be a very large window in cases where there were long gaps between recorded
 * requests from the capturing proxy.  For example, if a TrafficStream is read and it
 * that stream is scheduled to be run one hour later, readNextTrafficStreamChunk()
 * may not be called for almost an hour.  By design, we're not calling Kafka to pull
 * any more messages since we know that we don't have work to do for an hour.  Shortly
 * after the hour of waiting begins, Kakfa will notice that this application is no
 * longer calling poll and will kick the consumer out of the client group.  Other
 * consumers may connect, though they'll also be kicked out of the group shortly.
 *
 * See
 * <a href="https://kafka.apache.org/21/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html#failuredetection">...</a>
 *
 * "Basically if you don't call poll at least as frequently as the configured max interval,
 * then the client will proactively leave the group so that another consumer can take
 * over its partitions. When this happens, you may see an offset commit failure (as
 * indicated by a CommitFailedException thrown from a call to commitSync())."
 *
 * I believe that this can be mitigated, hopefully fully, by adding a keepAlive/do nothing
 * call that the BlockingTrafficSource can use.  That can be implemented in a source
 * like this with Kafka by polling, then resetting the position on the stream if we
 * aren't supposed to be reading new data.
 */
@Slf4j
public class KafkaProtobufConsumer implements ISimpleTrafficCaptureSource {

    @ToString(callSuper = true)
    private static class TrafficStreamKeyWithKafkaRecordId extends PojoTrafficStreamKey {
        private final int partition;
        private final long offset;

        TrafficStreamKeyWithKafkaRecordId(TrafficStream trafficStream, int partition, long offset) {
            super(trafficStream);
            this.partition = partition;
            this.offset = offset;
        }
    }

    private static class OffsetLifecycleTracker {
        private final PriorityQueue<Long> pQueue = new PriorityQueue<>();
        private long cursorHighWatermark;

        private OffsetLifecycleTracker() {
        }

        boolean isEmpty() {
            return pQueue.isEmpty();
        }

        void add(long offset) {
            cursorHighWatermark = offset;
            pQueue.add(offset);
        }

        Optional<Long> removeAndReturnNewHead(TrafficStreamKeyWithKafkaRecordId kafkaRecord) {
            var offsetToRemove = kafkaRecord.offset;
            var topCursor = pQueue.peek();
            var didRemove = pQueue.remove(offsetToRemove);
            assert didRemove : "Expected all live records to have an entry and for them to be removed only once";
            if (topCursor == offsetToRemove) {
                topCursor = Optional.ofNullable(pQueue.peek())
                        .orElse(cursorHighWatermark+1); // most recent cursor was previously popped
                log.atDebug().setMessage("Commit called for {}, and new topCursor={}")
                        .addArgument(offsetToRemove).addArgument(topCursor).log();
                return Optional.of(topCursor);
            } else {
                log.atDebug().setMessage("Commit called for {}, but topCursor={}")
                        .addArgument(offsetToRemove).addArgument(topCursor).log();
                return Optional.empty();
            }
        }
    }
  
    private static final MetricsLogger metricsLogger = new MetricsLogger("KafkaProtobufConsumer");

    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);

    private final Consumer<String, byte[]> kafkaConsumer;
    private final ConcurrentHashMap<Integer,OffsetLifecycleTracker> partitionToOffsetLifecycleTrackerMap;
    private final ConcurrentHashMap<TopicPartition,OffsetAndMetadata> nextSetOfCommitsMap;
    private final Object offsetLifecycleLock = new Object();
    private final String topic;
    private final KafkaBehavioralPolicy behavioralPolicy;
    private final AtomicInteger trafficStreamsRead;

    public KafkaProtobufConsumer(Consumer<String, byte[]> kafkaConsumer, String topic) {
        this(kafkaConsumer, topic, new KafkaBehavioralPolicy());
    }

    public KafkaProtobufConsumer(Consumer<String, byte[]> kafkaConsumer, @NonNull String topic,
                                 KafkaBehavioralPolicy behavioralPolicy) {
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.behavioralPolicy = behavioralPolicy;
        kafkaConsumer.subscribe(Collections.singleton(topic));
        trafficStreamsRead = new AtomicInteger();

        partitionToOffsetLifecycleTrackerMap = new ConcurrentHashMap<>();
        nextSetOfCommitsMap = new ConcurrentHashMap<>();
    }

    public static KafkaProtobufConsumer buildKafkaConsumer(@NonNull String brokers,
                                                           @NonNull String topic,
                                                           @NonNull String groupId,
                                                           boolean enableMSKAuth,
                                                           String propertyFilePath,
                                                           KafkaBehavioralPolicy behavioralPolicy) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        return new KafkaProtobufConsumer(new KafkaConsumer<>(kafkaProps), topic, behavioralPolicy);
    }

    public static Properties buildKafkaProperties(@NonNull String brokers,
                                                  @NonNull String groupId,
                                                  boolean enableMSKAuth,
                                                  String propertyFilePath) throws IOException {
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
            kafkaProps.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return kafkaProps;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
        return CompletableFuture.supplyAsync(this::readNextTrafficStreamSynchronously);
    }

    public List<ITrafficStreamWithKey> readNextTrafficStreamSynchronously() {
        try {
            ConsumerRecords<String, byte[]> records;
            records = safeCommitAndPollWithSwallowedRuntimeExceptions();
            Stream<ITrafficStreamWithKey> trafficStream = StreamSupport.stream(records.spliterator(), false)
                    .map(kafkaRecord -> {
                        try {
                            TrafficStream ts = TrafficStream.parseFrom(kafkaRecord.value());
                            // Ensure we increment trafficStreamsRead even at a higher log level
                            metricsLogger.atSuccess()
                                    .addKeyValue("connectionId", ts.getConnectionId())
                                    .addKeyValue("topicName", this.topic)
                                    .addKeyValue("sizeInBytes", ts.getSerializedSize())
                                    .setMessage("Parsed traffic stream from Kafka").log();
                            addOffset(kafkaRecord.partition(), kafkaRecord.offset());
                            var key = new TrafficStreamKeyWithKafkaRecordId(ts, kafkaRecord.partition(), kafkaRecord.offset());
                            log.atTrace().setMessage(()->"Parsed traffic stream #{}: {} {}")
                                    .addArgument(trafficStreamsRead.incrementAndGet())
                                    .addArgument(key)
                                    .addArgument(ts)
                                    .log();
                            return (ITrafficStreamWithKey) new PojoTrafficStreamWithKey(ts, key);
                        } catch (InvalidProtocolBufferException e) {
                            RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(kafkaRecord, e);
                            metricsLogger.atError(recordError)
                                    .addKeyValue("topicName", this.topic)
                                    .setMessage("Failed to parse traffic stream from Kafka.").log();
                            if (recordError != null) {
                                throw recordError;
                            }
                            return null;
                        }
            }).filter(Objects::nonNull);
            return trafficStream.collect(Collectors.<ITrafficStreamWithKey>toList());
        } catch (Exception e) {
            log.error("Terminating Kafka traffic stream");
            throw e;
        }
    }

    private ConsumerRecords<String, byte[]> safeCommitAndPollWithSwallowedRuntimeExceptions() {
        try {
            synchronized (offsetLifecycleLock) {
                if (!nextSetOfCommitsMap.isEmpty()) {
                    log.atDebug().setMessage(()->"Committing "+nextSetOfCommitsMap).log();
                    kafkaConsumer.commitSync(nextSetOfCommitsMap);
                    log.atDebug().setMessage(()->"Done committing "+nextSetOfCommitsMap).log();
                    nextSetOfCommitsMap.clear();
                }
            }

            var records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
            log.atInfo().setMessage(()->"Kafka consumer poll has fetched "+records.count()+" records").log();
            log.atDebug().setMessage(()->"All positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.position(tp)).collect(Collectors.joining(",")) + "}").log();
            log.atDebug().setMessage(()->"All COMMITTED positions: {"+kafkaConsumer.assignment().stream()
                    .map(tp->tp+": "+kafkaConsumer.committed(tp)).collect(Collectors.joining(",")) + "}").log();
            return records;
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: {} with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").addArgument(topic).log();
            return new ConsumerRecords<>(Collections.emptyMap());
        }
    }

    private void addOffset(int partition, long offset) {
        synchronized (offsetLifecycleLock) {
            var offsetTracker = partitionToOffsetLifecycleTrackerMap.computeIfAbsent(partition, p ->
                    new OffsetLifecycleTracker());
            offsetTracker.add(offset);
        }
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        if (!(trafficStreamKey instanceof TrafficStreamKeyWithKafkaRecordId)) {
            throw new IllegalArgumentException("Expected key of type "+TrafficStreamKeyWithKafkaRecordId.class+
                    " but received "+trafficStreamKey+" (of type="+trafficStreamKey.getClass()+")");
        }
        var kafkaTsk = (TrafficStreamKeyWithKafkaRecordId) trafficStreamKey;
        var p = kafkaTsk.partition;
        Optional<Long> newHeadValue;
        synchronized (offsetLifecycleLock) {
            var tracker = partitionToOffsetLifecycleTrackerMap.get(p);
            newHeadValue = tracker.removeAndReturnNewHead(kafkaTsk);
            newHeadValue.ifPresent(o -> {
                if (tracker.isEmpty()) {
                    partitionToOffsetLifecycleTrackerMap.remove(p);
                }
                nextSetOfCommitsMap.put(new TopicPartition(topic, p), new OffsetAndMetadata(o));
            });
        }
    }

    @Override
    public void close() throws IOException {
        kafkaConsumer.close();
        log.info("Kafka consumer closed successfully.");
    }
}
