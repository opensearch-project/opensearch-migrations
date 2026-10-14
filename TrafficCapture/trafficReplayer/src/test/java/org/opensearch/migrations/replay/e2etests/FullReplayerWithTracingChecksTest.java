package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.transform.TransformationLoader;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


@Tag("longTest")
public class FullReplayerWithTracingChecksTest extends FullTrafficReplayerTest {

    @Test
    void deployedChainEmitsPreservedSemanticMetricEvents() throws Exception {
        try (
            var telemetry = new InMemoryInstrumentationBundle(true, true);
            var server = SimpleNettyHttpServer.makeServer(false, request ->
                new SimpleHttpResponse(
                    Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                    "OK".getBytes(StandardCharsets.UTF_8),
                    "OK",
                    200
                )
            )
        ) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET /metrics HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                null,
                root
            );

            var metrics = telemetry.getFinishedMetrics();
            assertPositive(metrics, IReplayContexts.MetricNames.KAFKA_RECORD_READ);
            assertPositive(metrics, IReplayContexts.MetricNames.TRAFFIC_STREAMS_READ);
            assertPositive(metrics, IReplayContexts.MetricNames.TRANSFORM_SUCCESS);
            assertPositive(metrics, IReplayContexts.MetricNames.TRANSFORM_BYTES_OUT);
            assertPositive(metrics, IReplayContexts.MetricNames.CONNECTIONS_OPENED);
            assertPositive(metrics, IReplayContexts.MetricNames.CONNECTIONS_CLOSED);
            assertPositive(metrics, IReplayContexts.MetricNames.BYTES_WRITTEN_TO_TARGET);
            assertPositive(metrics, IReplayContexts.MetricNames.BYTES_READ_FROM_TARGET);
            assertPositive(metrics, IReplayContexts.MetricNames.TUPLE_COMPARISON);
        }
    }

    private static void assertPositive(
        java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String name
    ) {
        var value = metrics.stream()
            .filter(metric -> metric.getName().startsWith(name))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
            .sum();
        Assertions.assertTrue(
            value > 0,
            () -> "expected semantic metric event for " + name + "; metrics="
                + metrics.stream().map(metric -> metric.getName()).toList()
        );
    }
}
