package org.opensearch.migrations.replay.e2etests;

import javax.net.ssl.SSLException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.testutils.TrafficStreamFixtures;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.lifecycle.Startables;

@Tag("isolatedTest")
class OpenSearch37ReplayTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRANSFORMER_CONFIG = """
        [{
          "TypeMappingSanitizationTransformerProvider": {
            "sourceProperties": {"type": "ES", "version": {"major": 6, "minor": 8}},
            "regexMappings": [{
              "sourceIndexPattern": "(.*)",
              "sourceTypePattern": "(.*)",
              "targetIndexPattern": "$1"
            }]
          }
        }]
        """;

    private record Request(String method, String path, String body, int expectedStatus) {}
    private record ReplayResult(boolean transformed, JsonNode tuple) {}

    @Test
    @ResourceLock("TrafficReplayerRunner")
    void replayElasticsearch68TypedRequestsToOpenSearch37() throws Throwable {
        try (
            var source = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V3_7_0);
            var client = HttpClient.newHttpClient()
        ) {
            Startables.deepStart(source, target).join();
            var targetUri = URI.create(target.getUrl());
            try (var transformer = new TransformationLoader().getTransformerFactoryLoader(
                targetUri.getAuthority(), null, TRANSFORMER_CONFIG
            )) {
                var requests = List.of(
                    new Request("PUT", "/replay-test", """
                        {"settings":{"number_of_shards":1,"number_of_replicas":0},
                         "mappings":{"legacy":{"properties":{"message":{"type":"keyword"}}}}}
                        """, 200),
                    new Request("PUT", "/replay-test/legacy/1", "{\"message\":\"original\"}", 201),
                    new Request("POST", "/_bulk", """
                        {"index":{"_index":"replay-test","_type":"legacy","_id":"2"}}
                        {"message":"bulk"}
                        {"update":{"_index":"replay-test","_type":"legacy","_id":"1"}}
                        {"doc":{"message":"updated"}}
                        """, 200),
                    new Request("GET", "/replay-test/legacy/2", "", 200),
                    new Request("DELETE", "/replay-test/legacy/2", "", 200)
                );
                var streams = new ArrayList<TrafficStream>();
                for (int i = 0; i < requests.size(); i++) {
                    streams.add(sendAndRecord(client, source, i, requests.get(i)));
                }
                var trafficSource = new ArrayCursorTrafficSourceContext(streams);
                // Materialize tuple responses while their buffers and instrumentation contexts are still live.
                var results = new ConcurrentHashMap<Integer, ReplayResult>();
                TrafficReplayerRunner.runReplayer(
                    requests.size(),
                    (rootContext, threadPrefix) -> {
                        try {
                            // One outstanding request preserves create/index/update/delete dependencies across connections.
                            return new FullTrafficReplayerTest.TrafficReplayerWithWaitOnClose(
                                REQUEST_TIMEOUT, rootContext, targetUri, null, true, 1, 1, transformer, threadPrefix
                            );
                        } catch (SSLException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    () -> tuple -> {
                        var id = Integer.parseInt(tuple.getRequestKey().getTrafficStreamKey().getConnectionId());
                        results.put(id, new ReplayResult(
                            tuple.transformationStatus != null && tuple.transformationStatus.isCompleted(),
                            MAPPER.valueToTree(new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple))
                        ));
                    },
                    TestContext::noOtelTracking,
                    trafficSource,
                    new TimeShifter(10_000, Duration.ofMillis(100))
                );
                Assertions.assertEquals(requests.size(), results.size());
                Assertions.assertEquals(streams.size(), trafficSource.nextReadCursor.get(), "committed traffic records");
                var responses = new ArrayList<JsonNode>();
                for (int i = 0; i < requests.size(); i++) {
                    var result = results.get(i);
                    Assertions.assertNotNull(result, "Missing replay result for request " + i);
                    var tuple = result.tuple();
                    Assertions.assertTrue(result.transformed(), tuple::toString);
                    Assertions.assertFalse(tuple.has("error"), tuple::toString);
                    Assertions.assertEquals(0, tuple.path("numErrors").asInt(), tuple::toString);
                    Assertions.assertEquals(1, tuple.path("numRequests").asInt(), tuple::toString);
                    var response = tuple.path("targetResponses").get(0);
                    Assertions.assertEquals(requests.get(i).expectedStatus(),
                        response.path(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY).asInt(), tuple::toString);
                    responses.add(response.path(ParsedHttpMessagesAsDicts.PAYLOAD_KEY)
                        .path(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
                }
                Assertions.assertEquals(MAPPER.valueToTree(false), responses.get(2).path("errors"),
                    "Bulk HTTP 200 must not hide individual item failures");
                Assertions.assertEquals("bulk", responses.get(3).path("_source").path("message").asText());
                Assertions.assertEquals("deleted", responses.get(4).path("result").asText());

                var mapping = getJson(client, targetUri, "/replay-test/_mapping");
                Assertions.assertEquals("keyword",
                    mapping.path("replay-test").path("mappings").path("properties").path("message").path("type").asText());
                Assertions.assertFalse(mapping.path("replay-test").path("mappings").has("legacy"));
                Assertions.assertEquals("updated",
                    getJson(client, targetUri, "/replay-test/_doc/1").path("_source").path("message").asText());
                var refresh = client.send(HttpRequest.newBuilder(targetUri.resolve("/replay-test/_refresh"))
                    .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
                Assertions.assertEquals(200, refresh.statusCode(), refresh.body());
                Assertions.assertEquals(1, getJson(client, targetUri, "/replay-test/_count").path("count").asInt());
            }
        }
    }

    private static TrafficStream sendAndRecord(
        HttpClient client,
        SearchClusterContainer source,
        int requestId,
        Request request
    ) throws Exception {
        // Validate the original typed request against ES 6.8 before replaying its HTTP bytes.
        var sourceResponse = client.send(HttpRequest.newBuilder(URI.create(source.getUrl() + request.path()))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()))
            .build(), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(request.expectedStatus(), sourceResponse.statusCode(), sourceResponse.body());
        if (request.path().equals("/_bulk")) {
            Assertions.assertFalse(MAPPER.readTree(sourceResponse.body()).path("errors").asBoolean(),
                sourceResponse.body());
        }

        var requestText = request.method() + " " + request.path() + " HTTP/1.1\r\n"
            + "Host: " + URI.create(source.getUrl()).getAuthority() + "\r\n"
            + "Content-Type: application/json\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + request.body().getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n"
            + request.body();
        var responseText = "HTTP/1.1 " + sourceResponse.statusCode() + " OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + sourceResponse.body().getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n"
            + sourceResponse.body();
        return TrafficStreamFixtures.makeHttpRequestResponseTrafficStream(
            "es68-replay", Integer.toString(requestId), requestText, responseText
        );
    }

    private static JsonNode getJson(HttpClient client, URI cluster, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(cluster.resolve(path))
            .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
