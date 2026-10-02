package org.opensearch.migrations.replay.e2etests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    @Test
    void replayElasticsearch68TypedRequestsToOpenSearch37() throws Throwable {
        try (
            var source = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V3_7_0);
            var client = HttpClient.newHttpClient()
        ) {
            Startables.deepStart(source, target).join();
            var targetUri = URI.create(target.getUrl());
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
            var records = new ArrayList<CaptureRecord>();
            for (int i = 0; i < requests.size(); i++) {
                records.add(sendAndRecord(client, source, i, requests.get(i)));
            }
            var responses = new ArrayList<JsonNode>();
            for (int i = 0; i < requests.size(); i++) {
                // These requests are semantically dependent. Replay them in separate completed runs rather than
                // relying on a cross-connection FIFO guarantee that the rebuilt owner model does not provide.
                var result = FullTrafficReplayerTest.replayOne(
                    targetUri,
                    records.get(i),
                    () -> new TransformationLoader().getTransformerFactoryLoader(
                        targetUri.getAuthority(),
                        null,
                        TRANSFORMER_CONFIG
                    ),
                    null
                );
                Assertions.assertTrue(result.fatalFailures().isEmpty(), result.fatalFailures()::toString);
                Assertions.assertEquals(1, result.tuples().size());
                Assertions.assertEquals(1, result.transformationStatuses().size());
                Assertions.assertTrue(
                    result.transformationStatuses().getFirst().isCompleted()
                );
                Assertions.assertEquals(
                    1L,
                    result.committedOffsets().get(FullTrafficReplayerTest.TOPIC_PARTITION).offset(),
                    "committed traffic record " + i
                );
                var tuple = MAPPER.valueToTree(result.tuples().getFirst());
                Assertions.assertEquals(0, tuple.path("numErrors").asInt(), tuple::toString);
                Assertions.assertEquals(1, tuple.path("numRequests").asInt(), tuple::toString);
                var response = tuple.path("targetResponses").get(0);
                Assertions.assertEquals(
                    requests.get(i).expectedStatus(),
                    response.path(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY).asInt(),
                    tuple::toString
                );
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

    private static CaptureRecord sendAndRecord(
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
        var timestamp = Timestamp.newBuilder().setSeconds(1).build();
        var trafficStream = TrafficStream.newBuilder()
            .setNodeId("es68-replay")
            .setConnectionId(Integer.toString(requestId))
            .setNumberOfThisLastChunk(0)
            .addSubStream(observation(timestamp, 0).setRead(
                ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(requestText))
            ))
            .addSubStream(observation(timestamp, 1).setEndOfMessageIndicator(
                EndOfMessageIndication.getDefaultInstance()
            ))
            .addSubStream(observation(timestamp, 2).setWrite(
                WriteObservation.newBuilder().setData(ByteString.copyFromUtf8(responseText))
            ))
            .addSubStream(observation(timestamp, 3).setClose(
                CloseObservation.getDefaultInstance()
            ))
            .build();
        return CaptureRecord.newBuilder().setTrafficStream(trafficStream).build();
    }

    private static TrafficObservation.Builder observation(Timestamp timestamp, long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(timestamp)
            .setConnectionObservationSequence(sequence);
    }

    private static JsonNode getJson(HttpClient client, URI cluster, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(cluster.resolve(path))
            .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
