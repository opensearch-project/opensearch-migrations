package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;

import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.SneakyThrows;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for quiescent period tagging on resumed connections.
 */
class QuiescentConnectionTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    /**
     * A stream for a connection NOT in the active set and NOT starting with a READ observation
     * (i.e., another replayer was mid-connection) must be tagged with a non-null quiescentUntil.
     * Before fix: isResumedConnection() always returns null.
     */
    @Test
    @SneakyThrows
    void resumedConnection_taggedWithQuiescentUntil() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                // Stream with NO read observation (mid-connection, no open)
                var ts = TrafficStream.newBuilder()
                    .setNodeId("node1").setConnectionId("resumed-conn").setNumberOfThisLastChunk(0)
                    .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder().setSeconds(1).build())
                        .setWrite(WriteObservation.newBuilder()
                            .setData(ByteString.copyFromUtf8("HTTP/1.1 200 OK\r\n\r\n"))
                            .build())
                        .build())
                    .build();
                try (var baos = new ByteArrayOutputStream()) {
                    ts.writeTo(baos);
                    mc.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "k", baos.toByteArray()));
                } catch (Exception e) { throw new RuntimeException(e); }
            });

            var streams = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(streams.isEmpty());
            var stream = streams.get(0);

            Assertions.assertTrue(stream.isResumedConnection(),
                "resumed connection (no open, not in active set) must have isResumedConnection=true");
        }
    }

    /**
     * A stream starting with a READ observation for a new connection must NOT be tagged
     * (it's a fresh connection, not a resumed).
     */
    @Test
    @SneakyThrows
    void freshConnection_notTaggedWithQuiescentUntil() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                // Stream starting with a READ — fresh connection
                var ts = TrafficStream.newBuilder()
                    .setNodeId("node1").setConnectionId("fresh-conn").setNumberOfThisLastChunk(0)
                    .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder().setSeconds(1).build())
                        .setRead(ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                            .build())
                        .build())
                    .build();
                try (var baos = new ByteArrayOutputStream()) {
                    ts.writeTo(baos);
                    mc.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "k", baos.toByteArray()));
                } catch (Exception e) { throw new RuntimeException(e); }
            });

            var streams = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(streams.isEmpty());
            Assertions.assertFalse(streams.get(0).isResumedConnection(),
                "fresh connection (starts with READ) must have isResumedConnection=false");
        }
    }
}
