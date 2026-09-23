package org.opensearch.migrations.replay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.lifecycle.Startables;

@Tag("isolatedTest")
class OpenSearch37ReplayTest extends InstrumentationTest {
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
    void replayElasticsearch68TypedRequestsToOpenSearch37() throws Exception {
        try (
            var source = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V3_7_0);
            var client = HttpClient.newHttpClient()
        ) {
            Startables.deepStart(source, target).join();
            var targetUri = URI.create(target.getUrl());
            var eventLoops = new NioEventLoopGroup(1);
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
                        """, 200)
                );
                int requestId = 0;
                for (var request : requests) {
                    sendAndReplay(client, source, targetUri, eventLoops, transformer, requestId++, request);
                }

                var mapping = getJson(client, targetUri, "/replay-test/_mapping");
                Assertions.assertEquals("keyword",
                    mapping.path("replay-test").path("mappings").path("properties").path("message").path("type").asText());
                Assertions.assertFalse(mapping.path("replay-test").path("mappings").has("legacy"));
                Assertions.assertEquals("updated",
                    getJson(client, targetUri, "/replay-test/_doc/1").path("_source").path("message").asText());
                Assertions.assertEquals("bulk",
                    getJson(client, targetUri, "/replay-test/_doc/2").path("_source").path("message").asText());

                sendAndReplay(client, source, targetUri, eventLoops, transformer, requestId,
                    new Request("DELETE", "/replay-test/legacy/2", "", 200));
                var refresh = client.send(HttpRequest.newBuilder(targetUri.resolve("/replay-test/_refresh"))
                    .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
                Assertions.assertEquals(200, refresh.statusCode(), refresh.body());
                Assertions.assertEquals(1, getJson(client, targetUri, "/replay-test/_count").path("count").asInt());
            } finally {
                eventLoops.shutdownGracefully().sync();
            }
        }
    }

    private void sendAndReplay(
        HttpClient client,
        SearchClusterContainer source,
        URI targetUri,
        NioEventLoopGroup eventLoops,
        IJsonTransformer transformer,
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

        var requestBytes = (request.method() + " " + request.path() + " HTTP/1.1\r\n"
            + "Host: " + URI.create(source.getUrl()).getAuthority() + "\r\n"
            + "Content-Type: application/json\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + request.body().getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n"
            + request.body()).getBytes(StandardCharsets.UTF_8);
        try (var context = rootContext.getTestConnectionRequestContext("replay-" + requestId, 0)) {
            var session = new ConnectionReplaySession(eventLoops.next(), context.getChannelKeyContext(),
                NettyPacketToHttpConsumer.createClientConnectionFactory(null, targetUri));
            var sender = new NettyPacketToHttpConsumer(session, context, REQUEST_TIMEOUT);
            var consumer = new HttpJsonTransformingConsumer<>(transformer, null, sender, context);
            consumer.consumeBytes(requestBytes).get(REQUEST_TIMEOUT);
            var result = consumer.finalizeRequest().get(REQUEST_TIMEOUT);
            Assertions.assertTrue(result.transformationStatus.isCompleted());
            Assertions.assertNotNull(result.transformedOutput);
            Assertions.assertNull(result.transformedOutput.getError());
            Assertions.assertNotNull(result.transformedOutput.getRawResponse());
            Assertions.assertEquals(request.expectedStatus(), result.transformedOutput.getRawResponse().status().code());
        }
    }

    private static JsonNode getJson(HttpClient client, URI cluster, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(cluster.resolve(path))
            .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }
}
