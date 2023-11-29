package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import io.netty.buffer.Unpooled;
import io.opentelemetry.api.GlobalOpenTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.AbstractRecords;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.migrations.tracing.EmptyContext;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class KafkaCaptureFactoryTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    @Mock
    private Producer<String, byte[]> mockProducer;
    private String connectionId = "0242c0fffea82008-0000000a-00000003-62993a3207f92af6-9093ce33";
    private String topic = "test_topic";


    @Test
    public void testLargeRequestIsWithinKafkaMessageSizeLimit() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        int maxAllowableMessageSize = 1024*1024;
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        KafkaCaptureFactory kafkaCaptureFactory =
            new KafkaCaptureFactory(TEST_NODE_ID_STRING, producer, maxAllowableMessageSize);
        IChannelConnectionCaptureSerializer serializer = kafkaCaptureFactory.createOffloader(createCtx(), connectionId);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15000; i++) {
            sb.append("{ \"create\": { \"_index\": \"office-index\" } }\n{ \"title\": \"Malone's Cones\", \"year\": 2013 }\n");
        }
        Assertions.assertTrue(sb.toString().getBytes().length > 1024*1024);
        byte[] fakeDataBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        for (ProducerRecord<String, byte[]> record : producer.history()) {
            int recordSize = calculateRecordSize(record, null);
            Assertions.assertTrue(recordSize <= maxAllowableMessageSize);
            int largeIdRecordSize = calculateRecordSize(record, connectionId + ".9999999999");
            Assertions.assertTrue(largeIdRecordSize <= maxAllowableMessageSize);
        }
        bb.release();
        producer.close();
    }

    private static ConnectionContext createCtx() {
        return new ConnectionContext("test", "test",
                GlobalOpenTelemetry.getTracer("test").spanBuilder("test").startSpan());
    }

    /**
     * This size calculation is based off the KafkaProducer client request size validation check done when Producer
     * records are sent. This validation appears to be consistent for several versions now, here is a reference to
     * version 3.5 at the time of writing this: https://github.com/apache/kafka/blob/3.5/clients/src/main/java/org/apache/kafka/clients/producer/KafkaProducer.java#L1030-L1032.
     * It is, however, subject to change which may make this test scenario more suited for an integration test where
     * a KafkaProducer does not need to be mocked.
     */
    private int calculateRecordSize(ProducerRecord<String, byte[]> record, String recordKeySubstitute) {
        StringSerializer stringSerializer = new StringSerializer();
        ByteArraySerializer byteArraySerializer = new ByteArraySerializer();
        String recordKey = recordKeySubstitute == null ? record.key() : recordKeySubstitute;
        byte[] serializedKey = stringSerializer.serialize(record.topic(), record.headers(),  recordKey);
        byte[] serializedValue = byteArraySerializer.serialize(record.topic(), record.headers(), record.value());
        ApiVersions apiVersions = new ApiVersions();
        stringSerializer.close();
        byteArraySerializer.close();
        return AbstractRecords.estimateSizeInBytesUpperBound(apiVersions.maxUsableProduceMagic(),
            CompressionType.NONE, serializedKey, serializedValue, record.headers().toArray());
    }

    @Test
    public void testLinearOffloadingIsSuccessful() throws IOException {
        KafkaCaptureFactory kafkaCaptureFactory =
                new KafkaCaptureFactory(TEST_NODE_ID_STRING, mockProducer, 1024*1024);
        IChannelConnectionCaptureSerializer offloader = kafkaCaptureFactory.createOffloader(createCtx(), connectionId);

        List<Callback> recordSentCallbacks = new ArrayList<>(3);
        when(mockProducer.send(any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ProducerRecord<String, byte[]> record = (ProducerRecord) args[0];
            Callback recordSentCallback = (Callback) args[1];
            recordSentCallbacks.add(recordSentCallback);
            return null;
        });

        Instant ts = Instant.now();
        byte[] fakeDataBytes = "FakeData".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf1 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf2 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf3 = offloader.flushCommitAndResetStream(false);
        bb.release();

        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(0).onCompletion(generateRecordMetadata(topic, 1), null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(1).onCompletion(generateRecordMetadata(topic, 2), null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(2).onCompletion(generateRecordMetadata(topic, 1), null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(true, cf3.isDone());

        mockProducer.close();
    }

    private RecordMetadata generateRecordMetadata(String topicName, int partition) {
        TopicPartition topicPartition = new TopicPartition(topicName, partition);
        return new RecordMetadata(topicPartition, 0, 0, 0, 0, 0);
    }
}
