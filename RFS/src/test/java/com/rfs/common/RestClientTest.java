package com.rfs.common;

import com.rfs.tracing.TestContext;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RestClientTest {
    @Test
    public void testGetEmitsInstrumentation() throws Exception{
        var rootContext = TestContext.withAllTracking();
        try (var testServer = SimpleNettyHttpServer.makeServer(false, null,
                this::makeResponseContext)) {
            var restClient = new RestClient(new ConnectionDetails("http://localhost:" + testServer.port, null, null));
            try (var topScope = rootContext.createSnapshotCreateContext()) {
                restClient.getAsync("/createSnapshotDummy", topScope.createSnapshotContext()).block();
            }
        }
        var finishedSpans = rootContext.instrumentationBundle.getFinishedSpans();
        Assertions.assertTrue(!finishedSpans.isEmpty());
        Assertions.assertTrue(!rootContext.instrumentationBundle.getFinishedMetrics().isEmpty());

        Assertions.assertEquals(Set.of("createSnapshot", "httpRequest")
                        .stream().sorted().collect(Collectors.joining("\n")),
                finishedSpans.stream().map(SpanData::getName).sorted().collect(Collectors.joining("\n"))
        );
    }

    SimpleHttpResponse makeResponseContext(HttpRequestFirstLine firstLine) {
        var payloadBytes = "Hi".getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(Map.of("Content-Type", "text/plain"),
                payloadBytes, "OK", 200);
    }
}