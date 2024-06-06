package com.rfs.common;

import com.rfs.tracing.RfsContexts;
import com.rfs.tracing.TestContext;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static java.util.stream.Collectors.toList;

class RestClientTest {
    @Test
    public void testGetEmitsInstrumentation() throws Exception{
        var rootContext = TestContext.withAllTracking();
        try (var testServer = SimpleNettyHttpServer.makeServer(false, null,
                this::makeResponseContext)) {
            var restClient = new RestClient(new ConnectionDetails("http://localhost:" + testServer.port, null, null));
            try (var topScope = rootContext.createSnapshotCreateContext()) {
                restClient.postAsync("/", "empty", topScope.createSnapshotContext()).block();
                restClient.getAsync("/", topScope.createGetSnapshotContext()).block();
            }
        }

        Thread.sleep(200);
        var allMetricData = rootContext.instrumentationBundle.getFinishedMetrics();

        for (var kvp : Map.of(
                "createGetSnapshotContext", new long[]{133, 66},
                "createSnapshotContext", new long[]{139, 66},
                "", new long[]{272, 132}).entrySet()) {
            long bytesSent = allMetricData.stream().filter(md -> md.getName().startsWith("bytesSent"))
                    .reduce((a, b) -> b).get().getLongSumData().getPoints()
                    .stream()
                    .filter(pd -> pd.getAttributes().asMap().values().stream().map(o -> (String) o).collect(Collectors.joining())
                            .equals(kvp.getKey()))
                    .reduce((a, b) -> b).get().getValue();
            long bytesRead = allMetricData.stream().filter(md -> md.getName().startsWith("bytesRead"))
                    .reduce((a, b) -> b).get().getLongSumData().getPoints()
                    .stream()
                    .filter(pd -> pd.getAttributes().asMap().values().stream().map(o -> (String) o).collect(Collectors.joining())
                            .equals(kvp.getKey()))
                    .reduce((a, b) -> b).get().getValue();
            assertThat("Checking bytes {send, read} for context '" + kvp.getKey() + "'", new long[]{bytesSent, bytesRead}, equalTo(kvp.getValue()));
        }

        final var finishedSpans = rootContext.instrumentationBundle.getFinishedSpans();
        final var finishedSpanNames = finishedSpans.stream().map(SpanData::getName).collect(toList());
        assertThat(finishedSpanNames, containsInAnyOrder("httpRequest", "httpRequest", "createSnapshot"));

        final var httpRequestSpansByTime = finishedSpans.stream()
                .filter(sd -> sd.getName().equals("httpRequest"))
                .sorted(Comparator.comparing(SpanData::getEndEpochNanos)).collect(toList());
        int i = 0;
        for (var expectedBytes : List.of(
                new long[]{139,66},
                new long[]{133,66})) {
            var span = httpRequestSpansByTime.get(i++);
            long bytesSent = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_SENT_ATTR);
            long bytesRead = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_READ_ATTR);
            assertThat("Checking bytes {send, read} for httpRequest " + i, new long[]{bytesSent, bytesRead}, equalTo(expectedBytes));
        }
    }

    SimpleHttpResponse makeResponseContext(HttpRequestFirstLine firstLine) {
        var payloadBytes = "Hi".getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(Map.of("Content-Type", "text/plain",
                "content-length", payloadBytes.length+""
        ),
                payloadBytes, "OK", 200);
    }
}