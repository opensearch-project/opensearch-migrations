package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaCapabilityProbeTest {
    private static final String TOPIC = "traffic";

    @Test
    void publishesOneCaptureRecordProbePerRepresentativePartition() throws Exception {
        var producer = producer(101L, 102L);
        var probeIds = List.of("probe-a", "probe-b").iterator();

        var completion = CaptureKafkaCapabilityProbe.publish(
            producer,
            TOPIC,
            "activation",
            List.of(1, 3),
            probeIds::next
        );

        assertEquals(List.of(1, 3), producer.history().stream().map(record -> record.partition()).toList());
        for (int i = 0; i < producer.history().size(); ++i) {
            var record = producer.history().get(i);
            var envelope = CaptureRecord.parseFrom(record.value());
            assertEquals(
                CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE,
                envelope.getPayloadCase()
            );
            var probe = envelope.getCaptureCapabilityProbe();
            assertEquals("activation:PROBE", probe.getWriterNodeId());
            assertEquals(i == 0 ? "probe-a" : "probe-b", probe.getProbeId());
            assertEquals("activation:PROBE:" + probe.getProbeId(), record.key());
            assertEquals(0L, record.timestamp());
        }

        completion.get(1, TimeUnit.SECONDS);
    }

    @Test
    void anyFailedProbeFailsStartupQualification() {
        var producer = producer();
        var failure = new IllegalStateException("broker unavailable");
        producer.nextFailure = failure;

        var completion = CaptureKafkaCapabilityProbe.publish(
            producer,
            TOPIC,
            "activation",
            List.of(0, 1),
            () -> "probe-" + producer.history().size()
        );

        var observed = assertThrows(
            ExecutionException.class,
            () -> completion.get(1, TimeUnit.SECONDS)
        );
        assertEquals(failure, observed.getCause());
    }

    @Test
    void zeroLogAppendTimeRejectsStartupQualification() {
        var completion = CaptureKafkaCapabilityProbe.publish(
            producer(0L),
            TOPIC,
            "activation",
            List.of(0),
            () -> "probe"
        );

        var observed = assertThrows(
            ExecutionException.class,
            () -> completion.get(1, TimeUnit.SECONDS)
        );
        assertTrue(observed.getCause().getMessage().contains("positive broker-assigned timestamp"));
        assertTrue(observed.getCause().getMessage().contains("message.timestamp.type=LogAppendTime"));
    }

    @Test
    void missingLogAppendTimeRejectsStartupQualification() {
        var completion = CaptureKafkaCapabilityProbe.publish(
            producer(-1L),
            TOPIC,
            "activation",
            List.of(0),
            () -> "probe"
        );

        var observed = assertThrows(
            ExecutionException.class,
            () -> completion.get(1, TimeUnit.SECONDS)
        );
        assertTrue(observed.getCause().getMessage().contains("positive broker-assigned timestamp"));
    }

    private static TimestampingProducer producer(long... timestamps) {
        var producer = new TimestampingProducer();
        for (var timestamp : timestamps) {
            producer.timestamps.add(timestamp);
        }
        return producer;
    }

    private static final class TimestampingProducer extends MockProducer<String, byte[]> {
        private final Queue<Long> timestamps = new ArrayDeque<>();
        private RuntimeException nextFailure;

        private TimestampingProducer() {
            super(false, null, new StringSerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            super.send(record, (ignoredMetadata, ignoredFailure) -> {});
            if (nextFailure != null) {
                var failure = nextFailure;
                nextFailure = null;
                callback.onCompletion(null, failure);
                return CompletableFuture.failedFuture(failure);
            }
            var timestamp = timestamps.remove();
            var metadata = new RecordMetadata(
                new TopicPartition(record.topic(), record.partition()),
                0,
                0,
                timestamp,
                0,
                record.value().length
            );
            callback.onCompletion(metadata, null);
            return CompletableFuture.completedFuture(metadata);
        }
    }
}
