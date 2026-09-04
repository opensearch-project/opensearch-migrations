package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory<RecordMetadata>, AutoCloseable {

    public static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    public static final Duration DEFAULT_LIVENESS_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;

    private final IRootKafkaOffloaderContext rootScope;
    private final String nodeId;
    private final String topicNameForTraffic;
    private final int bufferSize;
    @Getter
    private final CaptureKafkaPublisher publisher;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize
    ) {
        this(
            rootScope,
            nodeId,
            producer,
            topicNameForTraffic,
            messageSize,
            null,
            DEFAULT_LIVENESS_SNAPSHOT_INTERVAL
        );
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize,
        Integer requestedShardWidth,
        Duration livenessSnapshotInterval
    ) {
        this(
            rootScope,
            nodeId,
            topicNameForTraffic,
            messageSize,
            new CaptureKafkaPublisher(
                producer,
                topicNameForTraffic,
                nodeId,
                PartitionRoutingPlan.discover(producer, topicNameForTraffic, nodeId, requestedShardWidth),
                new ProxyLivenessRegistry(),
                messageSize,
                livenessSnapshotInterval
            )
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        String topicNameForTraffic,
        int messageSize,
        CaptureKafkaPublisher publisher
    ) {
        this.rootScope = rootScope;
        this.nodeId = nodeId;
        this.topicNameForTraffic = topicNameForTraffic;
        this.bufferSize = messageSize - KAFKA_MESSAGE_OVERHEAD_BYTES;
        this.publisher = publisher;
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        int messageSize
    ) {
        this(rootScope, nodeId, producer, DEFAULT_TOPIC_NAME_FOR_TRAFFIC, messageSize);
    }

    @Override
    public IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(IConnectionContext ctx) {
        Objects.requireNonNull(
            ctx.getConnectionId(),
            "connectionId must not be null - partition locality requires a stable key"
        );
        var connectionId = ctx.getConnectionId();
        int partition = publisher.getRoutingPlan().partitionFor(connectionId);
        publisher.getLivenessRegistry().register(connectionId, partition);
        try {
            return new StreamChannelConnectionCaptureSerializer<>(
                nodeId,
                connectionId,
                partition,
                publisher.getRoutingPlan().getRoutingPlanId(),
                new StreamManager(rootScope, ctx, connectionId, partition)
            );
        } catch (RuntimeException | Error t) {
            publisher.getLivenessRegistry().remove(connectionId, partition);
            throw t;
        }
    }

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;

        @Override
        public int getOutputStreamBytesLimit() {
            return byteBuffer.limit();
        }

        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    class StreamManager extends OrderedStreamLifecyleManager<RecordMetadata> {
        IConnectionContext telemetryContext;
        IRootKafkaOffloaderContext rootScope;
        Instant startTime;
        String connectionId;
        int partition;

        public StreamManager(
            IRootKafkaOffloaderContext rootScope,
            IConnectionContext ctx,
            String connectionId,
            int partition
        ) {
            // TODO - add https://opentelemetry.io/blog/2022/instrument-kafka-clients/
            this.rootScope = rootScope;
            this.telemetryContext = ctx;
            this.startTime = Instant.now();
            this.connectionId = connectionId;
            this.partition = partition;
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            telemetryContext.addEvent("streamCreated");

            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public CompletableFuture<RecordMetadata> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            if (!(outputStreamHolder instanceof CodedOutputStreamWrapper)) {
                throw new IllegalArgumentException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamWrapper) outputStreamHolder;

            String recordId = String.format("%s.%d", connectionId, index);
            var byteBuffer = osh.byteBuffer;
            var payload = Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position());
            final boolean finalRecord;
            try {
                finalRecord = TrafficStream.parseFrom(payload).hasNumberOfThisLastChunk();
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }

            var flushContext = rootScope.createKafkaRecordContext(
                telemetryContext,
                topicNameForTraffic,
                recordId,
                payload.length
            );
            /*
             * The default KafkaProducer comes with built-in retry and error-handling logic that suits many cases. From the
             * documentation here for retry: https://kafka.apache.org/35/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html
             * "If the request fails, the producer can automatically retry. The retries setting defaults to Integer.MAX_VALUE,
             * and it's recommended to use delivery.timeout.ms to control retry behavior, instead of retries."
             *
             * Apart from this the KafkaProducer has logic for deciding whether an error is transient and should be
             * retried or not retried at all: https://kafka.apache.org/35/javadoc/org/apache/kafka/common/errors/RetriableException.html
             * as well as basic retry backoff
             */
            return publisher.publishTraffic(connectionId, partition, payload, finalRecord)
                .whenComplete(((recordMetadata, throwable) -> {
                if (throwable != null) {
                    flushContext.addTraceException(throwable, true);
                    log.error("Error sending producer record: {}", recordId, throwable);
                } else {
                    log.debug(
                        "Kafka producer record: {} has finished sending for topic: {} and partition {}",
                        recordId,
                        recordMetadata.topic(),
                        recordMetadata.partition()
                    );
                }
                flushContext.close();
            }));
        }
    }

    @Override
    public void close() {
        publisher.close();
    }
}
