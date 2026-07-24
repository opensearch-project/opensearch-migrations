package org.opensearch.migrations;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.S3FailedDocumentStreamSink;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Proves --failed-document-stream-enabled=false writes nothing to S3 even when a bucket resolves,
 * which is the case the opt-out exists for (on AWS the deployment default always resolves).
 *
 * <p>The opt-out run goes through the real production path: options arrive as the orchestrator sends
 * them (---INLINE-JSON), {@link RfsMigrateDocuments#buildFailedDocumentStreamSink} decides, and the
 * resulting sink is installed on a real client that then takes terminal bulk failures.
 *
 * <p>An enabled control runs the identical failure flow against a working sink. Without it, the
 * empty-prefix assertion would also pass if the harness simply produced no failures.
 */
@Tag("isolatedTest")
@Testcontainers
class RfsMigrateDocumentsFailedDocumentStreamOptOutLocalStackTest {

    private static final String BUCKET = "rfs-fds-opt-out-test";
    private static final String PREFIX = "rfs-failed-document-stream/";

    @Container
    static final LocalStackContainer LOCAL_STACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.3.0"))
        .withServices(LocalStackContainer.Service.S3);

    private static S3AsyncClient s3;

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
    void optedOutBackfillWritesNothingToS3() throws Exception {
        var sessionId = "sess-opt-out";

        // Options exactly as the orchestrator sends them: a fully resolved bucket AND the opt-out.
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build().parse(new String[]{
            "---INLINE-JSON",
            "{\"failedDocumentStreamEnabled\": false,"
                + "\"failedDocumentStreamS3Bucket\": \"" + BUCKET + "\","
                + "\"failedDocumentStreamS3Prefix\": \"" + PREFIX + "\","
                + "\"failedDocumentStreamS3Region\": \"" + LOCAL_STACK.getRegion() + "\","
                + "\"failedDocumentStreamS3Endpoint\": \"" + LOCAL_STACK.getEndpoint() + "\"}"
        });

        var sink = RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "worker-1", sessionId);
        assertThat("opt-out must yield no sink even with a resolved bucket", sink, is(nullValue()));

        driveTerminalBulkFailures(sink, sessionId);

        assertThat("opted-out run must leave the session prefix empty",
            listSessionKeys(sessionId), is(empty()));
    }

    @Test
    void controlEnabledBackfillWritesToS3() throws Exception {
        // Same failure flow with a working sink, so the assertion above cannot pass vacuously.
        var sessionId = "sess-control";
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket(BUCKET)
            .prefix(PREFIX)
            .sessionId(sessionId)
            .workerId("worker-1")
            .region(LOCAL_STACK.getRegion())
            .uploader(S3FailedDocumentStreamSink.s3ClientUploader(s3))
            .build();

        driveTerminalBulkFailures(sink, sessionId);
        sink.flush().block();
        sink.close();

        assertThat("control run must write records",
            listSessionKeys(sessionId).size(), is(greaterThan(0)));
    }

    /** Runs a bulk whose single document fails terminally. */
    private void driveTerminalBulkFailures(FailedDocumentStreamSink sink, String sessionId) {
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        var restClient = mock(RestClient.class);
        when(restClient.getConnectionContext()).thenReturn(connectionContext);

        // Non-retryable only, so the bulk terminates without needing the retry strategy overridden.
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null,
                buildBulkResponse(List.of(nonRetryableItem("bad-map"))))));

        var client = new OpenSearchClient_OS_2_11(
            restClient, mock(FailedRequestsLogger.class), Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED);
        client.setFailedDocumentStreamContext(sink, sessionId, "worker-1");
        client.setFailedDocumentStreamWorkItem("movies__0__0");

        // A non-retryable failure is recorded to the stream without failing the bulk overall.
        client.sendBulkRequest(
            "movies",
            List.of(createBulkDoc("bad-map")),
            mock(IRfsContexts.IRequestContext.class),
            false,
            new DocumentExceptionAllowlist(java.util.Set.of())
        ).block();
    }

    private List<String> listSessionKeys(String sessionId) {
        return s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET).prefix(PREFIX + "session=" + sessionId + "/").build())
            .join().contents().stream()
            .map(software.amazon.awssdk.services.s3.model.S3Object::key)
            .toList();
    }

    private static String buildBulkResponse(List<String> items) {
        return "{\"took\":1,\"errors\":true,\"items\":[" + String.join(",", items) + "]}";
    }

    private static String nonRetryableItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":400,"
            + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad\"}}}";
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
