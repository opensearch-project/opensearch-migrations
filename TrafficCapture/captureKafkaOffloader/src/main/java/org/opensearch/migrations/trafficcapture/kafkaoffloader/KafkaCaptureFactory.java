package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory {

    private static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";

    // Potential future optimization here to use a direct buffer (e.g. nio) instead of byte array
    private final Producer<String, byte[]> producer;
    private final String topicNameForTraffic;

    public KafkaCaptureFactory(Producer<String, byte[]> producer, String topicNameForTraffic) {
        // There is likely some default timeout/retry settings we should configure here to reduce any potential blocking
        // i.e. the Kafka cluster is unavailable
        this.producer = producer;
        this.topicNameForTraffic = topicNameForTraffic;
    }

    public KafkaCaptureFactory(Producer<String, byte[]> producer) {
        this(producer, DEFAULT_TOPIC_NAME_FOR_TRAFFIC);
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicLong supplierCallCounter = new AtomicLong();
        // This array is only an indirection to work around Java's constraint that lambda values are final
        CompletableFuture[] singleAggregateCfRef = new CompletableFuture[1];
        singleAggregateCfRef[0] = CompletableFuture.completedFuture(null);
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteStreamMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId, 100,
            () -> {
                ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);
                var cos = CodedOutputStream.newInstance(bb);
                codedStreamToByteStreamMap.put(cos, bb);
                return cos;
            },
            (codedOutputStream) -> {
                try {
                    ByteBuffer byteBuffer = codedStreamToByteStreamMap.get(codedOutputStream);
                    codedStreamToByteStreamMap.remove(codedOutputStream);
                    String recordId = String.format("%s_%d", connectionId, supplierCallCounter.incrementAndGet());
                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topicNameForTraffic, recordId,
                        Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position()));
                    // Used to essentially wrap Future returned by Producer to CompletableFuture
                    CompletableFuture cf = new CompletableFuture<>();
                    // Async request to Kafka cluster
                    producer.send(record, handleProducerRecordSent(cf, recordId));
                    // Note that ordering is not guaranteed to be preserved here
                    // A more desirable way to cut off our tree of cf aggregation should be investigated
                    singleAggregateCfRef[0] = singleAggregateCfRef[0].isDone() ? cf : CompletableFuture.allOf(singleAggregateCfRef[0], cf);
                    return singleAggregateCfRef[0];
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private Callback handleProducerRecordSent(CompletableFuture cf, String recordId) {
        return (metadata, exception) -> {
            cf.complete(metadata);

            if (exception != null) {
                log.error(String.format("Error sending producer record: %s", recordId), exception);
            }
            else if (log.isDebugEnabled()) {
                log.debug(String.format("Kafka producer record: %s has finished sending for topic: %s and partition %d",
                    recordId, metadata.topic(), metadata.partition()));
            }
        };
    }

}
