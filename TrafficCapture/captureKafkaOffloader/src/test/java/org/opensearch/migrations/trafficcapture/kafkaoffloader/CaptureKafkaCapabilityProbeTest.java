package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.trafficcapture.protos.CaptureCapabilityProbe;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaCapabilityProbeTest {
    private static final String TOPIC = "traffic";

    @Test
    void publishesOneReplayInertProbePerRepresentativeLeaderPartition() throws Exception {
        var producer = producer();
        var probeIds = List.of("probe-a", "probe-b").iterator();

        var completion = CaptureKafkaCapabilityProbe.publish(
            producer,
            TOPIC,
            "activation",
            List.of(1, 3),
            probeIds::next
        );

        assertEquals(List.of(1, 3), producer.history().stream().map(record -> record.partition()).toList());
        assertFalse(completion.isDone());
        for (int i = 0; i < producer.history().size(); ++i) {
            var record = producer.history().get(i);
            var probe = CaptureCapabilityProbe.parseFrom(record.value());
            assertEquals("activation:PROBE", probe.getWriterNodeId());
            assertEquals(i == 0 ? "probe-a" : "probe-b", probe.getProbeId());
            assertTrue(CaptureKafkaPublisher.isRecordType(
                record.headers(),
                CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE
            ));
            assertEquals(
                "activation:PROBE:" + probe.getProbeId(),
                record.key()
            );
        }

        assertTrue(producer.completeNext());
        assertFalse(completion.isDone());
        assertTrue(producer.completeNext());
        completion.get(1, TimeUnit.SECONDS);
    }

    @Test
    void anyFailedProbeFailsStartupQualification() {
        var producer = producer();
        var failure = new IllegalStateException("broker unavailable");
        var completion = CaptureKafkaCapabilityProbe.publish(
            producer,
            TOPIC,
            "activation",
            List.of(0, 1),
            () -> "probe-" + producer.history().size()
        );

        assertTrue(producer.errorNext(failure));

        var observed = assertThrows(
            ExecutionException.class,
            () -> completion.get(1, TimeUnit.SECONDS)
        );
        assertEquals(failure, observed.getCause());
    }

    @Test
    void probeWriterAndRecordTypeAreExplicit() throws Exception {
        var producer = producer();
        var completion = CaptureKafkaCapabilityProbe.publish(
            producer,
            TOPIC,
            "activation",
            List.of(0),
            () -> "probe"
        );
        var record = producer.history().get(0);

        assertEquals(
            CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE,
            new String(
                record.headers().lastHeader(CaptureRecordTypes.RECORD_TYPE_HEADER).value(),
                StandardCharsets.UTF_8
            )
        );
        assertEquals(
            "activation:PROBE",
            CaptureCapabilityProbe.parseFrom(record.value()).getWriterNodeId()
        );

        assertTrue(producer.completeNext());
        completion.get(1, TimeUnit.SECONDS);
    }

    private static MockProducer<String, byte[]> producer() {
        return new MockProducer<>(
            false,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
    }
}
