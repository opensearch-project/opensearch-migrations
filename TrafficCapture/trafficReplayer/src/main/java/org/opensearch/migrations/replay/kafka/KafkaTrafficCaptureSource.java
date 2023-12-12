package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
    // see https://stackoverflow.com/questions/39730126/difference-between-session-timeout-ms-and-max-poll-interval-ms-for-kafka-0-10
    public static final String DEFAULT_POLL_INTERVAL_MS = "60000";
    private static final MetricsLogger metricsLogger = new MetricsLogger("KafkaProtobufConsumer");


    final TrackingKafkaConsumer trackingKafkaConsumer;
    private final ExecutorService kafkaExecutor;
    private final AtomicLong trafficStreamsRead;
    private final KafkaBehavioralPolicy behavioralPolicy;

    public KafkaTrafficCaptureSource(Consumer<String, byte[]> kafkaConsumer, String topic, Duration keepAliveInterval) {
        this(kafkaConsumer, topic, keepAliveInterval, Clock.systemUTC(), new KafkaBehavioralPolicy());
    }

    public KafkaTrafficCaptureSource(Consumer<String, byte[]> kafkaConsumer,
                                     @NonNull String topic,
                                     Duration keepAliveInterval,
                                     Clock clock,
                                     @NonNull KafkaBehavioralPolicy behavioralPolicy)
    {
        trackingKafkaConsumer = new TrackingKafkaConsumer(kafkaConsumer, topic, keepAliveInterval, clock);
        trafficStreamsRead = new AtomicLong();
        this.behavioralPolicy = behavioralPolicy;
        kafkaConsumer.subscribe(Collections.singleton(topic), trackingKafkaConsumer);
        kafkaExecutor = Executors.newSingleThreadExecutor();
    }

    public static KafkaTrafficCaptureSource buildKafkaConsumer(@NonNull String brokers,
                                                               @NonNull String topic,
                                                               @NonNull String groupId,
                                                               boolean enableMSKAuth,
                                                               String propertyFilePath,
                                                               @NonNull Clock clock,
                                                               @NonNull KafkaBehavioralPolicy behavioralPolicy)
            throws IOException
    {
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        kafkaProps.putIfAbsent(MAX_POLL_INTERVAL_KEY, DEFAULT_POLL_INTERVAL_MS);
        var pollPeriod = Duration.ofMillis(Long.valueOf((String)kafkaProps.get(MAX_POLL_INTERVAL_KEY)));
        var keepAlivePeriod = getKeepAlivePeriodFromPollPeriod(pollPeriod);
        return new KafkaTrafficCaptureSource(new KafkaConsumer<>(kafkaProps),
                topic, keepAlivePeriod, clock, behavioralPolicy);
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
    @SneakyThrows
    public void touch() {
        CompletableFuture.runAsync(()->trackingKafkaConsumer.touch(), kafkaExecutor).get();
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
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
        log.atTrace().setMessage("readNextTrafficStreamChunk()").log();
        return CompletableFuture.supplyAsync(() -> {
            log.atTrace().setMessage("async...readNextTrafficStreamChunk()").log();
            return readNextTrafficStreamSynchronously();
        }, kafkaExecutor);
    }

    public List<ITrafficStreamWithKey> readNextTrafficStreamSynchronously() {
        log.atTrace().setMessage("readNextTrafficStreamSynchronously()").log();
        try {
            return trackingKafkaConsumer.getNextBatchOfRecords((offsetData,kafkaRecord) -> {
                        try {
                            TrafficStream ts = TrafficStream.parseFrom(kafkaRecord.value());
                            // Ensure we increment trafficStreamsRead even at a higher log level
                            metricsLogger.atSuccess(MetricsEvent.PARSED_TRAFFIC_STREAM_FROM_KAFKA)
                                    .setAttribute(MetricsAttributeKey.CONNECTION_ID, ts.getConnectionId())
                                    .setAttribute(MetricsAttributeKey.TOPIC_NAME, trackingKafkaConsumer.topic)
                                    .setAttribute(MetricsAttributeKey.SIZE_IN_BYTES, ts.getSerializedSize()).emit();

                            var trafficStreamsSoFar = trafficStreamsRead.incrementAndGet();
                            log.atTrace().setMessage(()->"Parsed traffic stream #" + trafficStreamsSoFar +
                                            ": " + offsetData + " " + ts).log();
                            var key = new TrafficStreamKeyWithKafkaRecordId(ts, offsetData);
                            return (ITrafficStreamWithKey) new PojoTrafficStreamWithKey(ts, key);
                        } catch (InvalidProtocolBufferException e) {
                            RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(kafkaRecord, e);
                            metricsLogger.atError(MetricsEvent.PARSING_TRAFFIC_STREAM_FROM_KAFKA_FAILED, recordError)
                                    .setAttribute(MetricsAttributeKey.TOPIC_NAME, trackingKafkaConsumer.topic).emit();
                            if (recordError != null) {
                                throw recordError;
                            }
                            return null;
                        }
            }).filter(Objects::nonNull).collect(Collectors.<ITrafficStreamWithKey>toList());
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Terminating Kafka traffic stream due to exception").log();
            throw e;
        }
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        if (!(trafficStreamKey instanceof TrafficStreamKeyWithKafkaRecordId)) {
            throw new IllegalArgumentException("Expected key of type "+TrafficStreamKeyWithKafkaRecordId.class+
                    " but received "+trafficStreamKey+" (of type="+trafficStreamKey.getClass()+")");
        }
        trackingKafkaConsumer.commitKafkaKey((TrafficStreamKeyWithKafkaRecordId) trafficStreamKey);
    }

    @Override
    public void close() throws IOException {
        trackingKafkaConsumer.close();
    }
}
