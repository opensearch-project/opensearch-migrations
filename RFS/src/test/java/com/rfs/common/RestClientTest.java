package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.tracing.RfsContexts;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

class RestClientTest {

    private static HttpClient makeSingleConnectionHttpClient() {
        var provider = ConnectionProvider.builder("singleConnection").maxConnections(1).build();
        return HttpClient.create(provider);
    }

    @Test
    public void testGetEmitsInstrumentation() throws Exception {
        var rootContext = SnapshotTestContext.factory().withAllTracking();
        try (var testServer = SimpleNettyHttpServer.makeServer(false, null, this::makeResponseContext)) {
            var restClient = new RestClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext(), makeSingleConnectionHttpClient());
            try (var topScope = rootContext.createSnapshotCreateContext()) {
                restClient.postAsync("/", "empty", topScope.createSnapshotContext()).block();
                restClient.getAsync("/", topScope.createGetSnapshotContext()).block();
            }
        }

        Thread.sleep(200);
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();

        for (var kvp : Map.of(
            "createGetSnapshotContext",
            new long[] { 101, 66 },
            "createSnapshotContext",
            new long[] { 139, 66 },
            "",
            new long[] { 240, 132 }
        ).entrySet()) {
            long bytesSent = allMetricData.stream()
                .filter(md -> md.getName().startsWith("bytesSent"))
                .reduce((a, b) -> b)
                .get()
                .getLongSumData()
                .getPoints()
                .stream()
                .filter(
                    pd -> pd.getAttributes()
                        .asMap()
                        .values()
                        .stream()
                        .map(o -> (String) o)
                        .collect(Collectors.joining())
                        .equals(kvp.getKey())
                )
                .reduce((a, b) -> b)
                .get()
                .getValue();
            long bytesRead = allMetricData.stream()
                .filter(md -> md.getName().startsWith("bytesRead"))
                .reduce((a, b) -> b)
                .get()
                .getLongSumData()
                .getPoints()
                .stream()
                .filter(
                    pd -> pd.getAttributes()
                        .asMap()
                        .values()
                        .stream()
                        .map(o -> (String) o)
                        .collect(Collectors.joining())
                        .equals(kvp.getKey())
                )
                .reduce((a, b) -> b)
                .get()
                .getValue();
            MatcherAssert.assertThat(
                "Checking bytes {send, read} for context '" + kvp.getKey() + "'",
                new long[] { bytesSent, bytesRead },
                Matchers.equalTo(kvp.getValue())
            );
        }

        final var finishedSpans = rootContext.inMemoryInstrumentationBundle.getFinishedSpans();
        final var finishedSpanNames = finishedSpans.stream().map(SpanData::getName).collect(Collectors.toList());
        MatcherAssert.assertThat(
            finishedSpanNames,
            Matchers.containsInAnyOrder("httpRequest", "httpRequest", "createSnapshot")
        );

        final var httpRequestSpansByTime = finishedSpans.stream()
            .filter(sd -> sd.getName().equals("httpRequest"))
            .sorted(Comparator.comparing(SpanData::getEndEpochNanos))
            .collect(Collectors.toList());
        int i = 0;
        for (var expectedBytes : List.of(new long[] { 139, 66 }, new long[] { 101, 66 })) {
            var span = httpRequestSpansByTime.get(i++);
            long bytesSent = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_SENT_ATTR);
            long bytesRead = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_READ_ATTR);
            MatcherAssert.assertThat(
                "Checking bytes {send, read} for httpRequest " + i,
                new long[] { bytesSent, bytesRead },
                Matchers.equalTo(expectedBytes)
            );
        }
    }

    SimpleHttpResponse makeResponseContext(HttpRequestFirstLine firstLine) {
        var payloadBytes = "Hi".getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(
            Map.of("Content-Type", "text/plain", "content-length", payloadBytes.length + ""),
            payloadBytes,
            "OK",
            200
        );
    }
}
