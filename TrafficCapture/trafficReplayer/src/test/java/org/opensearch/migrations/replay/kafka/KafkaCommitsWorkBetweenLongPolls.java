package org.opensearch.migrations.replay.kafka;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
public class KafkaCommitsWorkBetweenLongPolls {
    private static final long DEFAULT_POLL_INTERVAL_MS = 1000;
    private static final int NUM_RUNS = 5;
    public static final String TEST_TOPIC_NAME = "test-topic";
    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private InMemorySpanExporter testSpanExporter;
    private InMemoryMetricExporter testMetricExporter;

    @BeforeEach
    void setup() {
        GlobalOpenTelemetry.resetForTest();
        testSpanExporter = InMemorySpanExporter.create();
        testMetricExporter = InMemoryMetricExporter.create();

        OpenTelemetrySdk.builder()
                .setTracerProvider(
                        SdkTracerProvider.builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(testSpanExporter)).build())
                .setMeterProvider(
                        SdkMeterProvider.builder()
                                .registerMetricReader(PeriodicMetricReader.builder(testMetricExporter)
                                        .setInterval(Duration.ofMillis(100))
                                        .build())
                                .build())
                .buildAndRegisterGlobal();
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @SneakyThrows
    private KafkaConsumer<String, byte[]> buildKafkaConsumer() {
        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                "TEST_GROUP_CONSUMER_ID", false, null);
        kafkaConsumerProps.setProperty("max.poll.interval.ms", DEFAULT_POLL_INTERVAL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        log.atInfo().setMessage(()->"Just built KafkaConsumer="+kafkaConsumer).log();
        return kafkaConsumer;
    }

    @Test
    @Tag("longTest")
    public void testThatCommitsAndReadsKeepWorking() throws Exception {
        var kafkaSource = new KafkaTrafficCaptureSource(TestContext.singleton, buildKafkaConsumer(),
                TEST_TOPIC_NAME, Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS/3));
        var blockingSource = new BlockingTrafficSource(kafkaSource, Duration.ofMinutes(5));
        var kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        var itemQueue = new LinkedBlockingQueue<List<ITrafficStreamWithKey>>();
        blockingSource.stopReadsPast(Instant.EPOCH.plus(Duration.ofMillis(1)));

        new Thread(()->{
            try {
                for (int i=0; i<NUM_RUNS; ++i) {
                    sendNextMessage(kafkaProducer, i);
                    if (i > 0) {
                        blockingSource.stopReadsPast(getTimeAtPoint(i-1).plus(Duration.ofMillis(1)));
                    }
                    log.info("PUTMSG\n\n");
                    var chunks = itemQueue.take();
                    Assertions.assertEquals(1, chunks.size());
                    var ts = chunks.get(0);
                    Thread.sleep(DEFAULT_POLL_INTERVAL_MS*2);
                    log.info("committing "+ts.getKey());
                    blockingSource.commitTrafficStream(TestContext.singleton, ts.getKey());
                    blockingSource.stopReadsPast(getTimeAtPoint(i));
                }
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).start();

        for (int i=0; i<NUM_RUNS; ++i) {
            while (true) {
                var chunks = blockingSource.readNextTrafficStreamChunk(TestContext.singleton).get();
                if (!chunks.isEmpty()) {
                    Assertions.assertEquals(1, chunks.size());
                    log.info("GETMSG\n\n");
                    itemQueue.put(chunks);
                    break;
                }
            }
        }
//
//        var spans = testSpanExporter.getFinishedSpanItems();
//        Assertions.assertFalse(spans.isEmpty(), "No spans were found");
//
//        var metrics = testMetricExporter.getFinishedMetricItems();
//        Assertions.assertFalse(metrics.isEmpty(), "No metrics were found");

    }

    static Instant getTimeAtPoint(int i) {
        return Instant.EPOCH.plus(Duration.ofHours(1+i));
    }

    private void sendNextMessage(Producer<String, byte[]> kafkaProducer, int i) {
        var ts = KafkaTestUtils.makeTestTrafficStreamWithFixedTime(getTimeAtPoint(i), i);
        KafkaTestUtils.writeTrafficStreamRecord(kafkaProducer, ts, TEST_TOPIC_NAME,  ""+i);
    }
}
