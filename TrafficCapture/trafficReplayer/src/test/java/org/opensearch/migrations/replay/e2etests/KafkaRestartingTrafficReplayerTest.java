package org.opensearch.migrations.replay.e2etests;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.kafka.KafkaTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.kafka.KafkaStreamLoader;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import com.google.common.collect.Streams;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import static org.opensearch.migrations.replay.traffic.kafka.KafkaTestUtils.buildKafkaConsumer;
import static org.opensearch.migrations.replay.traffic.kafka.KafkaTestUtils.buildKafkaProducer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@WrapWithNettyLeakDetection(disableLeakChecks = true)
@Tag("isolatedTest")
public class KafkaRestartingTrafficReplayerTest extends InstrumentationTest {
    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000;
    public static final TrafficStream SENTINEL_TRAFFIC_STREAM = TrafficStream.newBuilder()
        .setConnectionId(SentinelSensingTrafficSource.SENTINEL_CONNECTION_ID)
        .build();

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
            var kafkaStreamLoader = new KafkaStreamLoader();
            kafkaStreamLoader.loadStreamsToKafka(
                buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers()),
                buildKafkaConsumer(embeddedKafkaBroker.getBootstrapServers(), DEFAULT_POLL_INTERVAL_MS),
                Streams.concat(trafficStreams.stream(), Stream.of(SENTINEL_TRAFFIC_STREAM)),
                TEST_TOPIC_NAME
            );
            TrafficReplayerRunner.runReplayer(
                streamAndConsumer.numHttpTransactions,
                httpServer.localhostEndpoint(),
                new CounterLimitedReceiverFactory(),
                () -> TestContext.noOtelTracking(),
                rootContext -> new SentinelSensingTrafficSource(
                    new KafkaTrafficCaptureSource(
                        rootContext,
                        buildKafkaConsumer(embeddedKafkaBroker.getBootstrapServers(), DEFAULT_POLL_INTERVAL_MS),
                        TEST_TOPIC_NAME,
                        Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS)
                    )
                )
            );
            httpServer.close();
            log.info("done");
        }
    }
}
