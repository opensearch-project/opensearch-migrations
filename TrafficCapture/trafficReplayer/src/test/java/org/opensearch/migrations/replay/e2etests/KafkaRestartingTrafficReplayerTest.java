package org.opensearch.migrations.replay.e2etests;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.V0_1TrafficCaptureSource;
import org.opensearch.migrations.replay.kafka.KafkaTestUtils;
import org.opensearch.migrations.replay.kafka.KafkaTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import com.google.common.collect.Streams;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@WrapWithNettyLeakDetection(disableLeakChecks = true)
@Tag("isolatedTest")
public class KafkaRestartingTrafficReplayerTest extends InstrumentationTest {
    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final TrafficStream SENTINEL_TRAFFIC_STREAM = TrafficStream.newBuilder()
        .setConnectionId(SentinelSensingTrafficSource.SENTINEL_CONNECTION_ID)
        .build();

    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS * 2);
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000;

    @Container
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final ConfluentKafkaContainer embeddedKafkaBroker = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    private static class CounterLimitedReceiverFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
        AtomicInteger nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        @Override
        public Consumer<SourceTargetCaptureTuple> get() {
            log.info("StopAt=" + nextStopPointRef.get());
            var stopPoint = nextStopPointRef.get();
            var counter = new AtomicInteger();
            return tuple -> {
                if (counter.incrementAndGet() > stopPoint) {
                    log.warn("Request received after our ingest threshold. Throwing.  Discarding " + tuple.context);
                    var nextStopPoint = stopPoint + new Random(stopPoint).nextInt(stopPoint + 1);
                    nextStopPointRef.compareAndSet(stopPoint, nextStopPoint);
                    throw new TrafficReplayerRunner.FabricatedErrorToKillTheReplayer(false);
                }
            };
        }
    }

    /**
     * Bisection test: runs a specific slice of the exhaustive seed list to isolate failures.
     * Adjust seedStart/seedEnd to narrow down which patterns cause hangs.
     */
    @Test
    @Disabled
    @Tag("isolatedTest")
    @ResourceLock("TrafficReplayerRunner")
    public void bisectExhaustiveSeeds() throws Throwable {
        var bisectStart = System.getProperty("bisect.start");
        var bisectEnd = System.getProperty("bisect.end");
        org.junit.jupiter.api.Assumptions.assumeTrue(bisectStart != null && bisectEnd != null,
            "bisect.start and bisect.end system properties not set — skipping bisection test");
        int seedStart = Integer.parseInt(bisectStart);
        int seedEnd = Integer.parseInt(bisectEnd);        var allSeeds = ExhaustiveTrafficStreamGenerator.RANDOM_GENERATOR_SEEDS_FOR_SUFFICIENT_TRAFFIC_VARIANCE;
        var seeds = allSeeds.subList(seedStart, Math.min(seedEnd, allSeeds.size()));
        log.atInfo().setMessage("bisectExhaustiveSeeds: running seeds {} to {} = {}")
            .addArgument(seedStart).addArgument(seedEnd).addArgument(seeds).log();

        var random = new Random(1);
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response -> TestHttpServerContext.makeResponse(random, response))) {
            var streamAndConsumer = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(
                TestContext.noOtelTracking(),
                seeds.stream().mapToInt(i -> i).sum(), // use sum as a proxy seed count — not ideal
                false
            );
            // Better: generate directly from the seed slice
            var generatedCases = ExhaustiveTrafficStreamGenerator.generateRandomTrafficStreamsAndSizes(
                TestContext.noOtelTracking(),
                seeds.stream().mapToInt(i -> i)
            ).toArray(ExhaustiveTrafficStreamGenerator.RandomTrafficStreamAndTransactionSizes[]::new);
            var trafficStreams = java.util.Arrays.stream(generatedCases)
                .flatMap(c -> java.util.Arrays.stream(c.trafficStreams))
                .collect(Collectors.toList());
            int numExpected = java.util.Arrays.stream(generatedCases)
                .mapToInt(c -> c.requestByteSizes.length).sum();

            loadStreamsToKafka(buildKafkaConsumer(),
                Streams.concat(trafficStreams.stream(), Stream.of(SENTINEL_TRAFFIC_STREAM)));
            TrafficReplayerRunner.runReplayer(
                numExpected,
                httpServer.localhostEndpoint(),
                new CounterLimitedReceiverFactory(),
                () -> TestContext.noOtelTracking(),
                rootContext -> new SentinelSensingTrafficSource(
                    new KafkaTrafficCaptureSource(rootContext, buildKafkaConsumer(), TEST_TOPIC_NAME,
                        Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS)))
            );
        }
    }

    @ParameterizedTest
    @CsvSource(value = { "3,false", "-1,false", "3,true", "-1,true", })
    @ResourceLock("TrafficReplayerRunner")
    public void fullTest(int testSize, boolean randomize) throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(2),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var streamAndConsumer = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(
                TestContext.noOtelTracking(),
                testSize,
                randomize
            );
            var trafficStreams = streamAndConsumer.stream.collect(Collectors.toList());
            log.atInfo().setMessage("{}")
                .addArgument(() -> trafficStreams.stream()
                    .map(TrafficStreamUtils::summarizeTrafficStream)
                    .collect(Collectors.joining("\n"))
                )
                .log();

            loadStreamsToKafka(
                buildKafkaConsumer(),
                Streams.concat(trafficStreams.stream(), Stream.of(SENTINEL_TRAFFIC_STREAM))
            );
            TrafficReplayerRunner.runReplayer(
                streamAndConsumer.numHttpTransactions,
                httpServer.localhostEndpoint(),
                new CounterLimitedReceiverFactory(),
                () -> TestContext.noOtelTracking(),
                rootContext -> new SentinelSensingTrafficSource(
                    new KafkaTrafficCaptureSource(
                        rootContext,
                        buildKafkaConsumer(),
                        TEST_TOPIC_NAME,
                        Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS)
                    )
                )
            );
            httpServer.close();
            log.info("done");
        }
    }

    @SneakyThrows
    private KafkaConsumer<String, byte[]> buildKafkaConsumer() {
        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            TEST_GROUP_CONSUMER_ID,
            false,
            null
        );
        kafkaConsumerProps.setProperty("max.poll.interval.ms", DEFAULT_POLL_INTERVAL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        log.atInfo().setMessage("Just built KafkaConsumer={}").addArgument(kafkaConsumer).log();
        return kafkaConsumer;
    }

    private void loadStreamsToKafka(KafkaConsumer<String, byte[]> kafkaConsumer, Stream<TrafficStream> streams)
        throws Exception {
        var kafkaProducer = buildKafkaProducer();
        var counter = new AtomicInteger();
        loadStreamsAsynchronouslyWithCloseableResource(
            kafkaConsumer,
            streams,
            s -> s.forEach(
                trafficStream -> KafkaTestUtils.writeTrafficStreamRecord(
                    kafkaProducer,
                    trafficStream,
                    TEST_TOPIC_NAME,
                    "KEY_" + counter.incrementAndGet()
                )
            )
        );
        Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
    }

    private <R extends AutoCloseable> void loadStreamsAsynchronouslyWithCloseableResource(
        KafkaConsumer<String, byte[]> kafkaConsumer,
        R closeableResource,
        Consumer<R> loader
    ) throws Exception {
        try {
            new Thread(() -> loader.accept(closeableResource)).start();
            var startTime = Instant.now();
            while (!kafkaConsumer.listTopics().isEmpty()) {
                Thread.sleep(10);
                Assertions.assertTrue(
                    Duration.between(startTime, Instant.now()).compareTo(MAX_WAIT_TIME_FOR_TOPIC) < 0
                );
            }
        } finally {
            closeableResource.close();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Producer<String, byte[]> buildKafkaProducer() {
        var kafkaProps = new Properties();
        kafkaProps.put(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer"
        );
        kafkaProps.put(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer"
        );
        // Property details:
        // https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, TEST_GROUP_PRODUCER_ID);
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBootstrapServers());
        try {
            return new KafkaProducer(kafkaProps);
        } catch (Exception e) {
            log.atError().setCause(e).log();
            throw e;
        }
    }

    private Supplier<ISimpleTrafficCaptureSource> loadStreamsToKafkaFromCompressedFile(
        TestContext rootCtx,
        KafkaConsumer<String, byte[]> kafkaConsumer,
        String filename,
        int recordCount
    ) throws Exception {
        var kafkaProducer = buildKafkaProducer();
        loadStreamsAsynchronouslyWithCloseableResource(
            kafkaConsumer,
            new V0_1TrafficCaptureSource(rootCtx, filename),
            originalTrafficSource -> {
                try {
                    for (int i = 0; i < recordCount; ++i) {
                        List<ITrafficStreamWithKey> chunks = null;
                        chunks = originalTrafficSource.readNextTrafficStreamChunk(rootCtx::createReadChunkContext)
                            .get();
                        for (int j = 0; j < chunks.size(); ++j) {
                            KafkaTestUtils.writeTrafficStreamRecord(
                                kafkaProducer,
                                chunks.get(j).getStream(),
                                TEST_TOPIC_NAME,
                                "KEY_" + i + "_" + j
                            );
                            Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
                        }
                    }
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }
        );
        return () -> new KafkaTrafficCaptureSource(
            rootCtx,
            kafkaConsumer,
            TEST_TOPIC_NAME,
            Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS)
        );
    }

}
