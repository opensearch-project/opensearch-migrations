package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.S3SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import io.netty.handler.codec.http.FullHttpRequest;
import lombok.extern.slf4j.Slf4j;

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

        ArrayList<Pair<FullHttpRequest, String>> capturedRequestList = new ArrayList<Pair<FullHttpRequest, String>>();
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    capturedRequestList.add(Pair.of(fl, fl.content().toString(StandardCharsets.UTF_8)));
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                    .host(endpoint)
                    .insecure(true)
                    .build()
                    .toConnectionContext());
            var snapshotCreator = new S3SnapshotCreator(
                    snapshotName,
                    sourceClient,
                    "s3://new-bucket",
                    "us-east-2",
                    List.of(),
                    snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.run(snapshotCreator);

            FullHttpRequest registerRepoRequest = capturedRequestList.get(0).getLeft();
            String registerRepoRequestContent = capturedRequestList.get(0).getRight();
            FullHttpRequest createSnapshotRequest = capturedRequestList.get(1).getLeft();
            String createSnapshotRequestContent = capturedRequestList.get(1).getRight();

            assert registerRepoRequest.uri().equals("/_snapshot/migration_assistant_repo");
            assert registerRepoRequest.method().toString().equals("PUT");
            assert registerRepoRequestContent.equals("{\"type\":\"s3\",\"settings\":{\"bucket\":\"new-bucket\",\"region\":\"us-east-2\",\"base_path\":null}}");
            assert createSnapshotRequest.uri().equals("/_snapshot/migration_assistant_repo/" + snapshotName);
            assert createSnapshotRequest.method().toString().equals("PUT");
            assert createSnapshotRequestContent.equals("{\"indices\":\"_all\",\"ignore_unavailable\":true,\"include_global_state\":true}");
        }
    }

    @Test
    public void testSnapshotCreateWithIndexAllowlist() throws Exception {
        var snapshotName = "my_snap";
        var indexAllowlist = List.of("allowed_index_1", "allowed_index_2");

        final FullHttpRequest[] createSnapshotRequest = new FullHttpRequest[1];
        final String[] createSnapshotRequestContent = {""};
        try (var destinationServer = SimpleNettyHttpServer.makeNettyServer(false,
                Duration.ofMinutes(10),
                fl -> {
                    if (fl.uri().equals("/_snapshot/migration_assistant_repo/" + snapshotName)) {
                        createSnapshotRequest[0] = fl;
                        createSnapshotRequestContent[0] = fl.content().toString(StandardCharsets.UTF_8);
                    }
                    return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
                }))
        {
            final var endpoint = destinationServer.localhostEndpoint().toString();

            var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
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

            assert createSnapshotRequest[0].method().toString().equals("PUT");
            assert createSnapshotRequestContent[0].contains(String.join(",", indexAllowlist));
        }
    }
}
