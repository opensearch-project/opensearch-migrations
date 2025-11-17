package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.S3SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

@Slf4j
public class TestCreateSnapshot {
    private static final byte[] ROOT_RESPONSE_ES_7_10_2 = "{\"version\": {\"number\": \"7.10.2\"}}".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> ROOT_RESPONSE_HEADERS = Map.of(
            "Content-Type",
            "application/json",
            "Content-Length",
            "" + ROOT_RESPONSE_ES_7_10_2.length
    );
    private static final byte[] CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED = "{\"persistent\":{\"compatibility\":{\"override_main_response_version\":\"false\"}}}".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> CLUSTER_SETTINGS_COMPATIBILITY_HEADERS = Map.of(
            "Content-Type",
            "application/json",
            "Content-Length",
            "" + CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED.length
    );

    final SnapshotTestContext snapshotContext = SnapshotTestContext.factory().noOtelTracking();
    final byte[] payloadBytes = "Success".getBytes(StandardCharsets.UTF_8);
    final Map<String, String> headers = Map.of(
            "Content-Type",
            "text/plain",
            "Content-Length",
            "" + payloadBytes.length
    );

    @Test
    public void testRepoRegisterAndSnapshotCreateRequests() throws Exception {
        var snapshotName = "my_snap";
        var snapshotRepoName = "my_snapshot_repo";

        ArrayList<Map.Entry<FullHttpRequest, String>> capturedRequestList = new ArrayList<>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    if (Objects.equals(fl.uri(), "/")) {
                        return new SimpleHttpResponse(ROOT_RESPONSE_HEADERS, ROOT_RESPONSE_ES_7_10_2, "OK", 200);
                    } else if (Objects.equals(fl.uri(), "/_cluster/settings?include_defaults=true")) {
                        return new SimpleHttpResponse(CLUSTER_SETTINGS_COMPATIBILITY_HEADERS, CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED,
                                "OK", 200);
                    }
                    capturedRequestList.add(new AbstractMap.SimpleEntry<>(fl, fl.content().toString(StandardCharsets.UTF_8)));
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(endpoint)
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new S3SnapshotCreator(
                    snapshotName,
                    snapshotRepoName,
                    sourceClient,
                    "s3://new-bucket/path-to-repo",
                    "us-east-2",
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.run(snapshotCreator);

            FullHttpRequest registerRepoRequest = capturedRequestList.get(0).getKey();
            String registerRepoRequestContent = capturedRequestList.get(0).getValue();
            FullHttpRequest createSnapshotRequest = capturedRequestList.get(1).getKey();
            String createSnapshotRequestContent = capturedRequestList.get(1).getValue();

            Assertions.assertEquals("/_snapshot/" + snapshotRepoName, registerRepoRequest.uri());
            Assertions.assertEquals(HttpMethod.PUT, registerRepoRequest.method());

            ObjectMapper objectMapper = new ObjectMapper();

            // Parse both JSON strings into JsonNode objects
            JsonNode actualRegisterRepoRequest = objectMapper.readTree(registerRepoRequestContent);
            // Verify default values: compress=false (default)
            JsonNode expectedRegisterRepoRequest = objectMapper
                    .createObjectNode()
                    .put("type", "s3")
                    .set("settings", objectMapper.createObjectNode()
                            .put("bucket", "new-bucket")
                            .put("region", "us-east-2")
                            .put("base_path", "path-to-repo")
                            .put("compress", false));

            Assertions.assertEquals(expectedRegisterRepoRequest, actualRegisterRepoRequest);

            JsonNode actualCreateSnapshotRequest = objectMapper.readTree(createSnapshotRequestContent);
            // Verify default values: include_global_state=true (default)
            JsonNode expectedCreateSnapshotRequest = objectMapper.createObjectNode()
                            .put("indices", "_all")
                            .put("ignore_unavailable", true)
                            .put("include_global_state", true);

            Assertions.assertEquals( "/_snapshot/" + snapshotRepoName + "/" + snapshotName, createSnapshotRequest.uri());
            Assertions.assertEquals(HttpMethod.PUT, createSnapshotRequest.method());
            Assertions.assertEquals(expectedCreateSnapshotRequest, actualCreateSnapshotRequest);
        }
    }

    @Test
    public void testSnapshotCreateWithIndexAllowlist() throws Exception {
        var snapshotName = "my_snap";
        var snapshotRepoName = "my_snapshot_repo";
        var indexAllowlist = List.of("allowed_index_1", "allowed_index_2");

        final AtomicReference<FullHttpRequest> createSnapshotRequest = new AtomicReference<>();
        final AtomicReference<String> createSnapshotRequestContent = new AtomicReference<>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    if (Objects.equals(fl.uri(), "/")) {
                        return new SimpleHttpResponse(ROOT_RESPONSE_HEADERS, ROOT_RESPONSE_ES_7_10_2, "OK", 200);
                    } else if (Objects.equals(fl.uri(), "/_cluster/settings?include_defaults=true")) {
                        return new SimpleHttpResponse(CLUSTER_SETTINGS_COMPATIBILITY_HEADERS, CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED,
                                "OK", 200);
                    } else if (fl.uri().equals("/_snapshot/" + snapshotRepoName + "/" + snapshotName)) {
                        createSnapshotRequest.set(fl);
                        createSnapshotRequestContent.set(fl.content().toString(StandardCharsets.UTF_8));
                    }
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(endpoint)
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new S3SnapshotCreator(
                    snapshotName,
                    snapshotRepoName,
                    sourceClient,
                    "s3://new-bucket",
                    "us-east-2",
                    indexAllowlist,
                    snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.run(snapshotCreator);

            Assertions.assertEquals(HttpMethod.PUT, createSnapshotRequest.get().method());
            assertThat(createSnapshotRequestContent.get(), containsString(String.join(",", indexAllowlist)));
        }
    }
}
