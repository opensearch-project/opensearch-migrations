package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        ArrayList<Map.Entry<FullHttpRequest, String>> capturedRequestList = new ArrayList<>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    capturedRequestList.add(new AbstractMap.SimpleEntry<>(fl, fl.content().toString(StandardCharsets.UTF_8)));
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClientFactory = new OpenSearchClientFactory(null);
            var sourceClient = sourceClientFactory.get(ConnectionContextTestParams.builder()
                    .host(endpoint)
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var snapshotCreator = new S3SnapshotCreator(
                    snapshotName,
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

            Assertions.assertEquals("/_snapshot/migration_assistant_repo", registerRepoRequest.uri());
            Assertions.assertEquals(HttpMethod.PUT, registerRepoRequest.method());

            ObjectMapper objectMapper = new ObjectMapper();

            // Parse both JSON strings into JsonNode objects
            JsonNode actualRegisterRepoRequest = objectMapper.readTree(registerRepoRequestContent);
            JsonNode expectedRegisterRepoRequest = objectMapper
                    .createObjectNode()
                    .put("type", "s3")
                    .set("settings", objectMapper.createObjectNode()
                            .put("bucket", "new-bucket")
                            .put("region", "us-east-2")
                            .put("base_path", "path-to-repo"));

            Assertions.assertEquals(expectedRegisterRepoRequest, actualRegisterRepoRequest);

            JsonNode actualCreateSnapshotRequest = objectMapper.readTree(createSnapshotRequestContent);
            JsonNode expectedCreateSnapshotRequest = objectMapper.createObjectNode()
                            .put("indices", "_all")
                            .put("ignore_unavailable", true)
                            .put("include_global_state", true);

            Assertions.assertEquals("/_snapshot/migration_assistant_repo/" + snapshotName, createSnapshotRequest.uri());
            Assertions.assertEquals(HttpMethod.PUT, createSnapshotRequest.method());
            Assertions.assertEquals(expectedCreateSnapshotRequest, actualCreateSnapshotRequest);
        }
    }

    @Test
    public void testSnapshotCreateWithIndexAllowlist() throws Exception {
        var snapshotName = "my_snap";
        var indexAllowlist = List.of("allowed_index_1", "allowed_index_2");

        final AtomicReference<FullHttpRequest> createSnapshotRequest = new AtomicReference<>();
        final AtomicReference<String> createSnapshotRequestContent = new AtomicReference<>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    if (fl.uri().equals("/_snapshot/migration_assistant_repo/" + snapshotName)) {
                        createSnapshotRequest.set(fl);
                        createSnapshotRequestContent.set(fl.content().toString(StandardCharsets.UTF_8));
                    }
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClientFactory = new OpenSearchClientFactory(null);
            var sourceClient = sourceClientFactory.get(ConnectionContextTestParams.builder()
                    .host(endpoint)
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var snapshotCreator = new S3SnapshotCreator(
                    snapshotName,
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
