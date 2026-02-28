package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.RootReplayerConstructorExtensions;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class SlowAndExpiredTrafficStreamBecomesTwoTargetChannelsTest {

    private static final Instant START_TIME = Instant.EPOCH.plus(Duration.ofDays(1));
    private static final long SPACING_SECONDS = 3600;
    private static final long TIME_SPEEDUP_FACTOR = SPACING_SECONDS / 2;
    private static final String TCP_CONNECTION_COUNT_METRIC_NAME = "tcpConnectionCount";
    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private static TrafficStream makeTrafficStream(
        Instant t,
        String connectionId,
        int tsRequestNumber,
        boolean last,
        Function<Supplier<TrafficObservation.Builder>, Stream<TrafficObservation.Builder>> trafficObservationFillers
    ) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        var streamBuilder = TrafficStream.newBuilder()
            .setConnectionId(connectionId)
            .setNodeId("testNodeId")
            .setPriorRequestsReceived(tsRequestNumber);
        streamBuilder = last
            ? streamBuilder.setNumberOfThisLastChunk(tsRequestNumber)
            : streamBuilder.setNumber(tsRequestNumber);
        return streamBuilder.addAllSubStream(
            trafficObservationFillers.apply(() -> TrafficObservation.newBuilder().setTs(fixedTimestamp))
                .map(b -> b.build())
                .collect(Collectors.toList())
        ).build();
    }

    private static String makeRequest(String connectionId, int index) {
        return "GET " + makePath(connectionId, index) + " HTTP/1.0\r\n\r\n";
    }

    private static TrafficStream makeTrafficStreamFor(
        String connectionId,
        int connectionIndex,
        boolean last,
        int timeShift
    ) {
        return makeTrafficStream(
            START_TIME.plus(Duration.ofSeconds(SPACING_SECONDS * timeShift)),
            connectionId,
            connectionIndex,
            last,
            b -> {
                var requestBytes = makeRequest(connectionId, connectionIndex + 0).getBytes(StandardCharsets.UTF_8);
                return Stream.of(
                    b.get().setRead(ReadObservation.newBuilder().setData(ByteString.copyFrom(requestBytes))),
                    b.get()
                        .setEndOfMessageIndicator(
                            EndOfMessageIndication.newBuilder()
                                .setFirstLineByteLength(requestBytes.length - 4)
                                .setHeadersByteLength(requestBytes.length - 4)
                                .build()
                        )
                );
            }
        );
    }

    @Test
    @Tag("longTest")
    public void test() throws Exception {
        TestContext rc = TestContext.withTracking(false, true);
        var responseTracker = new TrackingResponseBuilder(3);

        var trafficStreams = List.of(
            makeTrafficStreamFor("A", 0, false, 0),
            makeTrafficStreamFor("B", 0, true, 1),
            makeTrafficStreamFor("A", 1, true, 2)
        );
        var arraySource = new ArrayCursorTrafficCaptureSource(rc, new ArrayCursorTrafficSourceContext(trafficStreams));
        var trafficSource = new BlockingTrafficSource(arraySource, Duration.ofSeconds(SPACING_SECONDS));

        try (
            var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofSeconds(2), responseTracker);
            var replayer = new RootReplayerConstructorExtensions(
                rc,
                httpServer.localhostEndpoint(),
                new StaticAuthTransformerFactory("TEST"),
                new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                RootReplayerConstructorExtensions.makeNettyPacketConsumerConnectionPool(
                    httpServer.localhostEndpoint(),
                    true,
                    0,
                    "targetConnectionPool for SlowAndExpiredTrafficStreamBecomesTwoTargetChannelsTest"
                )
            )
        ) {
            new Thread(
                () -> responseTracker.onCountDownFinished(TEST_RESPONSE_TIMEOUT, () -> replayer.shutdown(null).join())
            );
            replayer.setupRunAndWaitForReplayWithShutdownChecks(
                Duration.ofMillis(1),
                TEST_RESPONSE_TIMEOUT,
                trafficSource,
                new TimeShifter(TIME_SPEEDUP_FACTOR),
                t -> {},
                Duration.ofSeconds(5)
            );
        }

        var matchingMetrics = rc.inMemoryInstrumentationBundle.getMetricsUntil(
            TCP_CONNECTION_COUNT_METRIC_NAME,
            IntStream.range(0, 5).map(i -> (i + 1000) * (int) (Math.pow(2, i))),
            filteredMetricList -> InMemoryInstrumentationBundle.reduceMetricStreamToSum(filteredMetricList.stream()) >= 3
        );
        Assertions.assertEquals(3, InMemoryInstrumentationBundle.reduceMetricStreamToSum(matchingMetrics.stream()));

        Assertions.assertEquals(3, responseTracker.pathsReceivedList.size());
        Map<String, ArrayList<String>> connectionToRequestUrisMap = new HashMap<>();
        for (var p : responseTracker.pathsReceivedList) {
            var parts = p.split("/");
            connectionToRequestUrisMap.putIfAbsent(parts[1], new ArrayList<>());
            connectionToRequestUrisMap.get(parts[1]).add(parts[2]);
        }
        Assertions.assertEquals(2, connectionToRequestUrisMap.get("A").size());
        Assertions.assertEquals(1, connectionToRequestUrisMap.get("B").size());
        connectionToRequestUrisMap.entrySet()
            .stream()
            .forEach(
                kvp -> Assertions.assertTrue(
                    IntStream.range(0, kvp.getValue().size())
                        .allMatch(i -> i == Integer.valueOf(kvp.getValue().get(i))),
                    "unordered " + kvp.getKey() + ": " + String.join(",", kvp.getValue())
                )
            );
    }

    static String makePath(String connection, int i) {
        return "/" + connection + "/" + Integer.toString(i);
    }

    private static class TrackingResponseBuilder implements Function<HttpRequest, SimpleHttpResponse> {
        List<String> pathsReceivedList;
        CountDownLatch targetRequestsPending;

        public TrackingResponseBuilder(int expected) {
            this.targetRequestsPending = new CountDownLatch(expected);
            this.pathsReceivedList = new ArrayList<>();
        }

        @Override
        public SimpleHttpResponse apply(HttpRequest firstLine) {
            var pathReceived = firstLine.getPath().getPath();
            pathsReceivedList.add(pathReceived);
            var payloadBytes = pathReceived.getBytes(StandardCharsets.UTF_8);
            targetRequestsPending.countDown();
            return new SimpleHttpResponse(Map.of("Content-Type", "text/plain"), payloadBytes, "OK", 200);
        }

        @SneakyThrows
        public void onCountDownFinished(Duration maxWaitTime, Runnable r) {
            targetRequestsPending.await(maxWaitTime.toMillis(), TimeUnit.MILLISECONDS);
            r.run();
        }
    }
}
