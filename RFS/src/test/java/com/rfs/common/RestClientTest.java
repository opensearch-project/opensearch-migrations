package com.rfs.common;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.SimpleHttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class RestClientTest {

    private InMemoryMetricReader metricReader;

    @BeforeEach
    public void setup() {
        metricReader = InMemoryMetricReader.create();
        final var meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metricReader)
            .build();

        OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .buildAndRegisterGlobal();
    }

    @Test
    public void testGetEmitsInstrumentation() throws Exception{
        try (var testServer = SimpleNettyHttpServer.makeServer(false, null, this::makeResponseContext)) {
            var restClient = new RestClient(new ConnectionDetails("http://localhost:" + testServer.port, null, null));
            restClient.postAsync("/", "empty").block();
            restClient.getAsync("/").block();
        }

        Thread.sleep(200);
        var allMetricData = metricReader.collectAllMetrics();

        for (var kvp : Map.of(
                "createGetSnapshotContext", new int[]{133, 66},
                "createSnapshotContext", new int[]{139, 66},
                "", new int[]{272, 132}).entrySet()) {
            long bytesSent = allMetricData.stream().filter(md -> md.getName().startsWith("bytesSent"))
                    .reduce((a, b) -> b).get().getLongSumData().getPoints()
                    .stream()
                    .filter(pd -> pd.getAttributes().asMap().values().stream().map(o -> (String) o).collect(Collectors.joining())
                            .equals(kvp.getKey()))
                    .reduce((a, b) -> b).get().getValue();
            Assertions.assertEquals(kvp.getValue()[0], bytesSent);


            long bytesRead = allMetricData.stream().filter(md -> md.getName().startsWith("bytesRead"))
                    .reduce((a, b) -> b).get().getLongSumData().getPoints()
                    .stream()
                    .filter(pd -> pd.getAttributes().asMap().values().stream().map(o -> (String) o).collect(Collectors.joining())
                            .equals(kvp.getKey()))
                    .reduce((a, b) -> b).get().getValue();
            Assertions.assertEquals(kvp.getValue()[1], bytesRead);
        }
        // var finishedSpans = rootContext.instrumentationBundle.getFinishedSpans().stream()
        //         .sorted(Comparator.comparing(SpanData::getName)
        //                 .thenComparing(s->s.getAttributes()
        //                         .get("RfsContexts.GenericRequestContext.CALL_TYPE_ATTR")).reversed())
        //         .collect(Collectors.toList());
        // Assertions.assertTrue(!finishedSpans.isEmpty());

        // try {
        //     Assertions.assertEquals(String.join("\n", List.of("httpRequest", "httpRequest", "createSnapshot")),
        //             finishedSpans.stream().map(SpanData::getName).collect(Collectors.joining("\n")));
        // } catch (Throwable e) {
        //     log.error(finishedSpans.stream().map(Object::toString).collect(Collectors.joining("\n")));
        //     throw e;
        // }


        // int i = 0;
        // for (var counts : List.of(
        //         new long[]{139,66},
        //         new long[]{133,66})) {
        //     var span = finishedSpans.get(i++);
        //     Assertions.assertEquals(span.getAttributes().get("b"),
        //             counts[0]);
        //     Assertions.assertEquals(span.getAttributes().get("r"),
        //             counts[1]);
        // }
    }

    SimpleHttpResponse makeResponseContext(HttpRequestFirstLine firstLine) {
        var payloadBytes = "Hi".getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(Map.of("Content-Type", "text/plain",
                "content-length", payloadBytes.length+""
        ),
                payloadBytes, "OK", 200);
    }
}