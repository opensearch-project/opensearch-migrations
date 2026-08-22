package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.S3FailedDocumentStreamSink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * End-to-end integration test that closes the seam between the two half-coverage tests:
 * {@code RfsFailedDocumentStreamIntegrationTest} exercises the real {@code executeBulkWithRetry}
 * failure→classify→emit path but with an in-process uploader, and
 * {@code S3FailedDocumentStreamSinkLocalStackTest} exercises the real S3 upload path but with
 * hand-built records. This test drives real bulk failures through {@code OpenSearchClient} into a
 * real (LocalStack) S3 via {@link S3FailedDocumentStreamSink#s3ClientUploader}, then lists and reads
 * the objects back out of the bucket — proving the records the pipeline actually produces survive the
 * real serialize → gzip → PutObject → list → get → gunzip round trip with the expected key layout,
 * classification, and content.
 */
@Tag("isolatedTest")
@Testcontainers
@ExtendWith(MockitoExtension.class)
class RfsFailedDocumentStreamLocalStackIntegrationTest {

    private static final String BUCKET = "rfs-fds-e2e-localstack-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final LocalStackContainer LOCAL_STACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.3.0"))
        .withServices(LocalStackContainer.Service.S3);

    private static S3AsyncClient s3;

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock(strictness = Strictness.LENIENT)
    ConnectionContext connectionContext;

    @BeforeAll
    static void setUp() {
        s3 = S3AsyncClient.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join();
    }

    @AfterAll
    static void tearDown() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Test
    void realBulkFailuresLandInS3WithExpectedLayoutAndContent() throws Exception {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);

        // One bulk with a success, an allowlisted "success", a non-retryable failure, and a retryable
        // failure that never recovers. Only bad-map (NON_RETRYABLE) and throttled (RETRYABLE_EXHAUSTED)
        // should end up in S3.
        var firstResponse = buildBulkResponse(List.of(
            successItem("ok-1"),
            allowlistedItem("dup-1"),
            nonRetryableItem("bad-map"),
            retryableItem("throttled")
        ));
        var subsequentResponse = buildBulkResponse(List.of(retryableItem("throttled")));
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, firstResponse)))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, subsequentResponse)));

        var sink = S3FailedDocumentStreamSink.builder()
            .bucket(BUCKET)
            .prefix("rfs-failed-document-stream/")
            .sessionId("sess-e2e")
            .workerId("worker-1")
            .region(LOCAL_STACK.getRegion())
            .uploader(S3FailedDocumentStreamSink.s3ClientUploader(s3))   // the REAL SDK-backed uploader
            .build();

        var allowlist = new DocumentExceptionAllowlist(java.util.Set.of("version_conflict_engine_exception"));
        var failedRequestsLogger = mock(FailedRequestsLogger.class);
        var client = spy(new OpenSearchClient_OS_2_11(
            restClient, failedRequestsLogger, Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED));
        client.setFailedDocumentStreamContext(sink, "sess-e2e", "worker-1");
        client.setFailedDocumentStreamWorkItem("movies__0__0");
        doReturn(Retry.fixedDelay(2, Duration.ofMillis(1))).when(client).getBulkRetryStrategy();

        assertThrows(Exception.class, () -> client.sendBulkRequest(
            "movies",
            List.of(createBulkDoc("ok-1"), createBulkDoc("dup-1"), createBulkDoc("bad-map"), createBulkDoc("throttled")),
            mock(IRfsContexts.IRequestContext.class),
            false,
            allowlist
        ).block());

        // Mirror the workflow's lease-completion contract: flush (make durable) before "completing".
        sink.flush().block();
        sink.close();

        // ─── Read the real objects back out of the bucket ───────────────────────────
        var sessionPrefix = "rfs-failed-document-stream/session=sess-e2e/";
        var objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET).prefix(sessionPrefix).build())
            .join().contents();
        var keys = objects.stream().map(S3Object::key).toList();

        // Every object is a per-index, per-worker gzipped ndjson file under this session.
        assertThat("expected at least one object written", objects.size() >= 1, equalTo(true));
        for (var key : keys) {
            assertThat(key, org.hamcrest.Matchers.containsString(sessionPrefix));
            assertThat(key, org.hamcrest.Matchers.containsString("/index=movies/"));
            assertThat(key, org.hamcrest.Matchers.containsString("/worker=worker-1/failed-document-stream-"));
            assertThat(key, org.hamcrest.Matchers.containsString(".ndjson.gz"));
        }

        var records = new ArrayList<JsonNode>();
        for (var key : keys) {
            records.addAll(readGzippedRecords(key));
        }
        var byId = new java.util.HashMap<String, JsonNode>();
        for (var r : records) {
            byId.put(r.path("documentId").asText(), r);
        }

        // Exactly the two terminal failures — the success and the allowlisted item are absent.
        assertThat(byId.keySet(), containsInAnyOrder("bad-map", "throttled"));
        assertThat(byId.get("bad-map").path("failureClass").asText(), equalTo("NON_RETRYABLE"));
        assertThat(byId.get("bad-map").path("failureType").asText(), equalTo("mapper_parsing_exception"));
        assertThat(byId.get("throttled").path("failureClass").asText(), equalTo("RETRYABLE_EXHAUSTED"));
        assertThat(byId.get("throttled").path("failureType").asText(), equalTo("es_rejected_execution_exception"));
        // Records carry the work-item context stamped on the client.
        assertThat(byId.get("bad-map").path("workItemId").asText(), equalTo("movies__0__0"));
        assertThat(byId.get("throttled").path("targetIndex").asText(), equalTo("movies"));
    }

    private List<JsonNode> readGzippedRecords(String key) throws Exception {
        var bytes = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                AsyncResponseTransformer.toBytes())
            .join().asByteArray();
        String decoded;
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            decoded = new String(gz.readAllBytes());
        }
        var out = new ArrayList<JsonNode>();
        for (var line : decoded.strip().split("\n")) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    // ─── Helpers (mirrors RfsFailedDocumentStreamIntegrationTest) ─────────────────────

    private static String buildBulkResponse(List<String> items) {
        return "{\"took\":1,\"errors\":true,\"items\":[" + String.join(",", items) + "]}";
    }

    private static String successItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id
            + "\",\"result\":\"created\",\"status\":201}}";
    }

    private static String allowlistedItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":409,"
            + "\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"dup\"}}}";
    }

    private static String nonRetryableItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":400,"
            + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad\"}}}";
    }

    private static String retryableItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":429,"
            + "\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}}";
    }

    private static BulkOperationSpec createBulkDoc(String docId) {
        var bulkDoc = mock(IndexOp.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        var operation = mock(IndexOperationMeta.class);
        when(operation.getId()).thenReturn(docId);
        when(bulkDoc.getOperation()).thenReturn(operation);
        when(bulkDoc.getOperationType()).thenReturn(OperationType.INDEX);
        when(bulkDoc.isIncludeDocument()).thenReturn(true);
        when(bulkDoc.getDocument()).thenReturn(Map.of("field", "value"));
        return bulkDoc;
    }
}
