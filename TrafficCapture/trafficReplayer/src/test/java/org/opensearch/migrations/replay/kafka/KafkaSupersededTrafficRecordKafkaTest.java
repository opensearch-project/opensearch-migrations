package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.traffic.source.SourceInput;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;
import org.opensearch.migrations.trafficcapture.protos.ProxyNoMoreWrites;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
class KafkaSupersededTrafficRecordKafkaTest extends InstrumentationTest {
    private static final String TOPIC = "superseded-traffic";
    private static final String GROUP = "superseded-traffic-reader";
    private static final String STALE_NODE = "stale-writer";
    private static final String SURVIVING_NODE = "surviving-writer";

    @Container
    private final ConfluentKafkaContainer kafka =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void peerDeclarationDiscardsAndCommitsLaterTrafficWithAMetric() throws Exception {
        try (var producer = KafkaTestUtils.buildKafkaProducer(kafka.getBootstrapServers())) {
            publish(
                producer,
                STALE_NODE + ":no-more-writes:0",
                ProxyNoMoreWrites.newBuilder()
                    .setNodeId(STALE_NODE)
                    .setPartition(0)
                    .setDeclaredBy(SURVIVING_NODE)
                    .setEmittedAtMillis(1)
                    .build()
                    .toByteArray(),
                CaptureRecordTypes.NO_MORE_WRITES_RECORD_TYPE
            );
            var staleTraffic = KafkaTestUtils.makeTestTrafficStreamWithFixedTime(Instant.EPOCH, 0)
                .toBuilder()
                .setNodeId(STALE_NODE)
                .setConnectionId("stale-connection")
                .setPartition(0)
                .setRoutingPlanId("plan")
                .setNumberOfThisLastChunk(0)
                .build();
            publish(
                producer,
                "stale-connection",
                staleTraffic.toByteArray(),
                CaptureRecordTypes.TRAFFIC_RECORD_TYPE
            );
        }

        var properties = KafkaTrafficCaptureSource.buildKafkaProperties(
            kafka.getBootstrapServers(),
            GROUP,
            "none",
            null,
            null,
            null
        );
        try (
            var consumer = new KafkaConsumer<String, byte[]>(properties);
            var source = new KafkaTrafficCaptureSource(
                rootContext,
                consumer,
                TOPIC,
                Duration.ofMillis(100),
                Clock.systemUTC(),
                new KafkaBehavioralPolicy(),
                TrackingKafkaConsumer.UNBOUNDED_OWNED_RECORDS,
                TrackingKafkaConsumer.UNBOUNDED_OWNED_BYTES,
                false
            )
        ) {
            var records = readAtLeast(source, 2);
            var declaration = assertInstanceOf(KafkaNoMoreWritesRecord.class, records.get(0));
            var discarded = assertInstanceOf(KafkaSupersededTrafficRecord.class, records.get(1));

            assertEquals(0, discarded.getDeclarationOffset());
            assertTrue(source.partitionToActiveConnections.isEmpty());
            assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    rootContext.inMemoryInstrumentationBundle.getFinishedMetrics(),
                    IKafkaConsumerContexts.MetricNames.SUPERSEDED_TRAFFIC_RECORDS_DISCARDED
                )
            );

            source.commitTrafficStreamAsync(declaration.getKey()).get(10, TimeUnit.SECONDS);
            source.commitTrafficStreamAsync(discarded.getKey()).get(10, TimeUnit.SECONDS);
        }
    }

    private List<SourceInput> readAtLeast(
        KafkaTrafficCaptureSource source,
        int expected
    ) throws Exception {
        var records = new ArrayList<SourceInput>();
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            records.addAll(
                source.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                    .get(5, TimeUnit.SECONDS)
            );
        }
        assertEquals(expected, records.size());
        return records;
    }

    private void publish(
        org.apache.kafka.clients.producer.Producer<String, byte[]> producer,
        String key,
        byte[] value,
        String recordType
    ) throws Exception {
        producer.send(
            new ProducerRecord<>(
                TOPIC,
                0,
                null,
                key,
                value,
                List.of(new RecordHeader(
                    CaptureRecordTypes.RECORD_TYPE_HEADER,
                    recordType.getBytes(StandardCharsets.UTF_8)
                ))
            )
        ).get(10, TimeUnit.SECONDS);
    }
}
