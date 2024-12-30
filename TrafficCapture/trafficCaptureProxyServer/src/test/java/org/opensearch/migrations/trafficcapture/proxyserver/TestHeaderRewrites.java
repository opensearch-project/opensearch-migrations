package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.CaptureProxyContainer;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class TestHeaderRewrites {

    public static final String ONLY_FOR_HEADERS_VALUE = "this is only for headers";
    public static final String BODY_WITH_HEADERS_CONTENTS = "\n" +
        "body: should stay\n" +
        "body: untouched\n" +
        "body:\n";

    @Test
    public void testHeaderRewrites() throws Exception {
        final var payloadBytes = "Success".getBytes(StandardCharsets.UTF_8);
        final var headers = Map.of(
            "Content-Type",
            "text/plain",
            "Content-Length",
            "" + payloadBytes.length
        );
        var rewriteArgs = List.of(
            "--setHeader",
            "host",
            "localhost",
            "--setHeader",
            "X-new-header",
            "insignificant value"
        );
        var capturedRequestList = new ArrayList<HttpRequest>();
        try (var destinationServer = SimpleNettyHttpServer.makeServer(false,
            Duration.ofMinutes(10),
            fl -> {
                capturedRequestList.add(fl);
                log.trace("headers: " + fl.getHeaders().stream().map(kvp->kvp.getKey()+": "+kvp.getValue())
                    .collect(Collectors.joining()));
                return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
            });
             var proxy = new CaptureProxyContainer(() -> destinationServer.localhostEndpoint().toString(), null,
                 rewriteArgs.stream());
             var client = new SimpleHttpClientForTesting())
        {
            proxy.start();
            final var proxyEndpoint = CaptureProxyContainer.getUriFromContainer(proxy);

            var allHeaders = new LinkedHashMap<String, String>();
            allHeaders.put("Host", "localhost");
            allHeaders.put("User-Agent", "UnitTest");
            var response = client.makeGetRequest(new URI(proxyEndpoint), allHeaders.entrySet().stream());
            var capturedRequest = capturedRequestList.get(capturedRequestList.size()-1).getHeaders().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Assertions.assertEquals("localhost", capturedRequest.get("host"));
            Assertions.assertEquals("insignificant value", capturedRequest.get("X-new-header"));
        }
    }

    @Test
    public void testBodyDoesntRewrite() throws Exception {
        final var payloadBytes = "Success".getBytes(StandardCharsets.UTF_8);
        final var headers = Map.of(
            "Content-Type",
            "text/plain",
            "Content-Length",
            "" + payloadBytes.length
        );
        var rewriteArgs = List.of(
            "--setHeader",
            "host",
            "localhost",
            "--setHeader",
            "body",
            ONLY_FOR_HEADERS_VALUE
        );
        var capturedRequestList = new ArrayList<HttpRequest>();
        var capturedBodies = new ArrayList<String>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
            Duration.ofMinutes(10),
            fullRequest -> {
                var request = new SimpleNettyHttpServer.RequestToAdapter(fullRequest);
                capturedRequestList.add(request);
                log.atTrace().setMessage("headers: {}").addArgument(() ->
                    request.getHeaders().stream().map(kvp->kvp.getKey()+": "+kvp.getValue())
                        .collect(Collectors.joining())).log();
                 capturedBodies.add(fullRequest.content().toString(StandardCharsets.UTF_8));
                return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
            });
             var proxy = new CaptureProxyContainer(() -> destinationServer.localhostEndpoint().toString(), null,
                 rewriteArgs.stream());
             var client = new SimpleHttpClientForTesting();
             var bodyStream = new ByteArrayInputStream(BODY_WITH_HEADERS_CONTENTS.getBytes(StandardCharsets.UTF_8)))
        {
            proxy.start();
            final var proxyEndpoint = CaptureProxyContainer.getUriFromContainer(proxy);

            var allHeaders = new LinkedHashMap<String, String>();
            allHeaders.put("Host", "localhost");
            allHeaders.put("User-Agent", "UnitTest");
            var response = client.makePutRequest(new URI(proxyEndpoint), allHeaders.entrySet().stream(),
                new SimpleHttpClientForTesting.PayloadAndContentType(bodyStream, "text/plain"));
            log.error("response=" + response);
            var capturedRequest = capturedRequestList.get(capturedRequestList.size()-1).getHeaders().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Assertions.assertEquals("localhost", capturedRequest.get("host"));
            Assertions.assertEquals(ONLY_FOR_HEADERS_VALUE, capturedRequest.get("body"));

            var lastBody = capturedBodies.get(capturedBodies.size()-1);
            Assertions.assertEquals(BODY_WITH_HEADERS_CONTENTS, lastBody);
        }
    }
}
