package org.opensearch.migrations.bulkload.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SizeLimitingProxy;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.transformers.BulkOperationTransformer;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.S3FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real OpenSearch, the production bootstrap/checkpoint path, real gzip failed-document files, and
 * controlled network failures. Each restart constructs a new client, sink, source and bootstrap
 * using the checkpoint persisted by its predecessor. Source ordering is deliberately not requested:
 * the staging sink must enforce it even when the configured batch concurrency is eight.
 */
@Tag("isolatedTest")
class RfsStagedRecoveryEndToEndTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int LIMIT = 8192;
    private static final String BUCKET = "rfs-staged-recovery";
    private static final LocalStackContainer S3_CONTAINER = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.3.0")).withServices(LocalStackContainer.Service.S3);
    private static S3AsyncClient s3;
    private static final String LARGE = "{\"early_field\":\"" + "é雪😀-".repeat(4000)
        + "\",\"late_field\":{\"number\":17,\"nullable\":null,\"array\":[1,true,\"雪\"]},"
        + "\"precise\":0.123456789012345678901234567890123456789}";
    @TempDir Path directory;

    @BeforeAll
    static void startS3() {
        S3_CONTAINER.start();
        s3 = S3AsyncClient.builder().endpointOverride(S3_CONTAINER.getEndpoint())
            .region(Region.of(S3_CONTAINER.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                S3_CONTAINER.getAccessKey(), S3_CONTAINER.getSecretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join();
    }

    @AfterAll
    static void stopS3() {
        if (s3 != null) s3.close();
        S3_CONTAINER.close();
    }

    static Stream<ContainerVersion> targets() {
        return Stream.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_LATEST);
    }

    @ParameterizedTest(name = "checkpoint recovery, fencing and DLQ on {0}")
    @MethodSource("targets")
    void verifiesRecoveryAndFailureContracts(ContainerVersion version) throws Exception {
        try (var target = new SearchClusterContainer(version).withEnv("http.max_content_length", LIMIT + "b")) {
            target.start();
            var cluster = new ClusterOperations(target);
            resumesLaterChunkFailureAndFlushesWholeDocument(target, cluster);
            fencesDelayedPredecessorBeforeItCanPublish(target, cluster);
            waitsForRunningTaskBeforeCheckpointReplay(target, cluster);
            refusesBlindReplayAfterLostSubmissionAcknowledgement(target, cluster);
            recordsRealLateFieldMappingFailure(target, cluster);
            recordsEncodingFailureAfterEarlierChunksCommitted(target, cluster);
            preservesOrderAcrossMixedBulkItemStatuses(target, cluster);
        }
    }

    private void resumesLaterChunkFailureAndFlushesWholeDocument(
        SearchClusterContainer target, ClusterOperations cluster
    ) throws Exception {
        String index = "recover-chunks";
        String sourceIndex = "recover-chunks-source";
        String targetId = "renamed-partial";
        createIndex(cluster, index);
        assertEquals(201, cluster.put("/" + index + "/_doc/" + targetId, "{\"obsolete\":true,\"early_field\":\"partial\"}").getKey());
        var before = source(cluster, index, targetId);
        var documents = List.of(doc("done", "{\"value\":1}", 0), doc("partial", "{\"source\":\"original\"}", 1),
            doc("later", "{\"value\":3}", 2));
        var checkpoint = checkpoint(index);
        var durable = new CompletableFuture<Void>();
        var flushStarted = new CountDownLatch(1);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles(durable, flushStarted);
             var logs = new CloseableLogSetup(FailedRequestsLogger.FAILED_REQUESTS_LOGGER)) {
            var rejected = new AtomicReference<SizeLimitingProxy.Request>();
            var loseCreateAcknowledgement = new AtomicBoolean(true);
            proxy.afterForward = (request, response) -> request.path().contains("/_create/upload-")
                && response.status() == 201 && loseCreateAcknowledgement.compareAndSet(true, false) ? null : response;
            proxy.intercept = request -> {
                if (request.path().contains("/_update/") && text(request.body()).contains("\"late_field\"")) {
                    rejected.set(request);
                    return response(400, "{\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"later chunk rejected\"}}");
                }
                return null;
            };
            BulkOperationTransformer transform = operations -> {
                operations.forEach(operation -> {
                    ((IndexOp) operation).getOperation().setIndex(index);
                    if ("partial".equals(operation.getOperation().getId())) {
                        ((IndexOp) operation).getOperation().setId(targetId);
                        operation.setRawDocument(bytes(LARGE));
                    }
                });
                return operations;
            };
            var first = worker(target, proxy, sourceIndex, documents, checkpoint, transform,
                new FailedRequestsLogger(logs.getTestLogger()), failures);
            var failedRun = CompletableFuture.supplyAsync(first::run);
            assertTrue(flushStarted.await(30, TimeUnit.SECONDS));
            assertFalse(failedRun.isDone(), "A staged failure must await durable DLQ flush before reporting the batch error");
            assertEquals(1, readCheckpoint(checkpoint), "Only the earlier complete document may checkpoint");
            assertEquals(before, source(cluster, index, targetId), "No partially assembled target source is visible");
            assertEquals(404, cluster.get("/" + index + "/_doc/later").getKey());
            durable.complete(null);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> failedRun.get(30, TimeUnit.SECONDS));
            var records = failures.records();
            assertEquals(1, records.size(), "One logical failure, not a separate DLQ entry for every chunk");
            var record = records.get(0);
            assertEquals(index, record.path("targetIndex").asText(), "Use the transformed destination, not the source collection");
            assertEquals(targetId, record.path("documentId").asText());
            var action = tree(record.path("transformedRequestBody").asText().split("\n")[0]).path("index");
            assertEquals(index, action.path("_index").asText());
            assertEquals(targetId, action.path("_id").asText());
            assertEquals("{\"source\":\"original\"}", record.path("originalDocument").asText());
            assertEquals(LARGE, record.path("transformedRequestBody").asText().split("\n")[1],
                "Preserve the whole transformed document and its exact decimal spelling");
            assertEquals("CHUNK", record.path("stagedFailure").path("stage").asText());
            assertTrue(record.path("stagedFailure").path("chunkSequence").asLong() >= 2);
            assertFalse(record.path("stagedFailure").path("publicationMayHaveCommitted").asBoolean());
            assertEquals(text(rejected.get().body()), record.path("stagedFailure").path("requestBody").asText());
            String uploadId = record.path("stagedFailure").path("uploadId").asText();
            var partialStage = source(cluster, sourceIndex + "-staging", uploadId);
            assertTrue(partialStage.path("next").asLong() > 0, "Several chunks really committed before the later chunk failed");
            assertEquals(1, logs.getCapturedLogEvents().size());
            var logged = logs.getCapturedLogEvents().get(0).getContextData();
            assertEquals(record.path("transformedRequestBody").asText(), logged.get("requestBody"));
            assertEquals(record.path("originalDocument").asText(), logged.get("originalDocument"));
            assertEquals(record.path("stagedFailure"), tree(logged.get("stagedFailure")));
            assertFalse(loseCreateAcknowledgement.get(), "The initial create really committed before its response was lost");
            assertTrue(proxy.completedRequests.stream().anyMatch(exchange -> exchange.request().path().contains("/_create/upload-")
                && exchange.response().status() == 409), "A retry must not reset already initialized staging state");

            proxy.intercept = request -> null;
            var second = worker(target, proxy, sourceIndex, documents, checkpoint, transform, new FailedRequestsLogger(), failures);
            assertEquals(CompletionStatus.WORK_COMPLETED, second.run());
            assertEquals(List.of(1L), second.source.readOffsets, "The successor must read from the persisted cursor");
            assertEquals(3, readCheckpoint(checkpoint));
            assertEquals(tree(LARGE), source(cluster, index, targetId));
            assertFalse(source(cluster, index, targetId).has("obsolete"), "Publication fully replaces a pre-existing partial document");
            assertEquals(tree("{\"value\":3}"), source(cluster, index, "later"));
            assertEquals(1, failures.records().size());
            assertTrue(second.cursor.get().isShardComplete());
            assertBounds(proxy);
        } finally {
            durable.complete(null);
        }
    }

    private void fencesDelayedPredecessorBeforeItCanPublish(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "fence-predecessor";
        createIndex(cluster, index);
        var documents = List.of(doc("same", LARGE, 0), delete("same", 1), doc("later", "{\"value\":2}", 2));
        var checkpoint = checkpoint(index);
        try (var firstProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var secondProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var publicationClaimStarted = new CountDownLatch(1);
            var releaseOldClaim = new CountDownLatch(1);
            var delayedClaim = new AtomicReference<SizeLimitingProxy.Request>();
            firstProxy.beforeForward = request -> {
                if (request.method().equals("PUT") && request.path().contains("/_doc/journal-")
                    && "publishing".equals(tree(request.body()).path("phase").asText())) {
                    delayedClaim.set(request);
                    publicationClaimStarted.countDown();
                    await(releaseOldClaim);
                }
            };
            var predecessor = worker(target, firstProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            var oldRun = CompletableFuture.supplyAsync(predecessor::run);
            try {
                assertTrue(publicationClaimStarted.await(30, TimeUnit.SECONDS));
                assertEquals(0, readCheckpoint(checkpoint));
                var successor = worker(target, secondProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
                assertEquals(CompletionStatus.WORK_COMPLETED, successor.run());
                assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "The successor's later delete has completed");
                assertEquals(3, readCheckpoint(checkpoint));
            } finally {
                releaseOldClaim.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> oldRun.get(30, TimeUnit.SECONDS));
            assertTrue(firstProxy.completedRequests.stream().anyMatch(exchange ->
                exchange.request() == delayedClaim.get() && exchange.response().status() == 409),
                "The real cluster must reject the stale publication CAS");
            assertEquals(0, publications(firstProxy), "An abandoned worker must never submit its reindex after the successor");
            assertEquals(1, publications(secondProxy));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "No late publication may resurrect the deleted document");
            assertEquals("CLAIM_PUBLICATION", failures.records().get(0).path("stagedFailure").path("stage").asText());
            assertBounds(firstProxy);
            assertBounds(secondProxy);
        }
    }

    private void waitsForRunningTaskBeforeCheckpointReplay(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "recover-task";
        createIndex(cluster, index);
        // This target shard cannot satisfy writes requiring all replicas until the test releases it.
        assertEquals(200, cluster.put("/" + index + "/_settings",
            "{\"number_of_replicas\":1,\"index.write.wait_for_active_shards\":\"all\"}").getKey());
        var documents = List.of(doc("same", LARGE, 0), delete("same", 1), doc("later", "{\"value\":2}", 2));
        var checkpoint = checkpoint(index);
        try (var firstProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var secondProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            firstProxy.intercept = request -> request.path().startsWith("/_tasks/")
                ? response(403, "{\"error\":{\"type\":\"security_exception\",\"reason\":\"lost access to task status\"}}") : null;
            var predecessor = worker(target, firstProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            assertThrows(RfsException.class, predecessor::run);
            assertEquals(0, readCheckpoint(checkpoint));
            var failed = failures.records().get(0);
            assertEquals("WAIT_PUBLICATION", failed.path("stagedFailure").path("stage").asText());
            assertFalse(failed.path("stagedFailure").path("taskId").asText().isEmpty());
            assertTrue(failed.path("stagedFailure").path("publicationMayHaveCommitted").asBoolean());
            var taskObservedRunning = new CountDownLatch(1);
            secondProxy.afterForward = (request, response) -> {
                if (request.path().startsWith("/_tasks/") && !tree(response.body()).path("completed").asBoolean(true)) {
                    taskObservedRunning.countDown();
                }
                return response;
            };
            var successor = worker(target, secondProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            var recovered = CompletableFuture.supplyAsync(successor::run);
            try {
                assertTrue(taskObservedRunning.await(15, TimeUnit.SECONDS), "A real asynchronous publication must still be running");
                assertFalse(recovered.isDone());
                assertTrue(successor.source.readOffsets.isEmpty(), "Wait for publication before reading/replaying any source documents");
                assertEquals(0, publications(secondProxy));
                assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey());
            } finally {
                assertEquals(200, cluster.put("/" + index + "/_settings", "{\"number_of_replicas\":0}").getKey());
            }
            assertEquals(CompletionStatus.WORK_COMPLETED, recovered.get(60, TimeUnit.SECONDS));
            assertEquals(3, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "A completed old task cannot race the replayed delete");
            assertEquals(tree("{\"value\":2}"), source(cluster, index, "later"));
            assertEquals(1, publications(firstProxy));
            assertEquals(1, publications(secondProxy), "Replay starts a fresh full replacement after the previous task terminates");
            assertBounds(firstProxy);
            assertBounds(secondProxy);
        }
    }

    private void refusesBlindReplayAfterLostSubmissionAcknowledgement(
        SearchClusterContainer target, ClusterOperations cluster
    ) throws Exception {
        String index = "unknown-publication";
        createIndex(cluster, index);
        var documents = List.of(doc("same", LARGE, 0), delete("same", 1));
        var checkpoint = checkpoint(index);
        try (var firstProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var secondProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var actualTask = new AtomicReference<String>();
            firstProxy.afterForward = (request, response) -> {
                if (request.path().startsWith("/_reindex")) {
                    assertEquals(200, response.status());
                    actualTask.set(tree(response.body()).path("task").asText());
                    return null;
                }
                return response;
            };
            assertThrows(RfsException.class,
                worker(target, firstProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures)::run);
            assertEquals(1, publications(firstProxy), "The transport must not retry a task submission after a lost ACK");
            assertNotNull(actualTask.get());
            var finished = cluster.get("/_tasks/" + actualTask.get() + "?wait_for_completion=true&timeout=30s");
            assertEquals(200, finished.getKey());
            assertTrue(tree(finished.getValue()).path("completed").asBoolean());
            assertEquals(tree(LARGE), source(cluster, index, "same"), "Publication actually committed despite the lost ACK");
            var failed = failures.records().get(0);
            assertEquals("SUBMIT_PUBLICATION", failed.path("stagedFailure").path("stage").asText());
            assertTrue(failed.path("stagedFailure").path("publicationMayHaveCommitted").asBoolean());
            assertTrue(failed.path("stagedFailure").path("taskId").isNull());
            assertEquals(LARGE, failed.path("transformedRequestBody").asText().split("\n")[1]);
            assertEquals(failed.path("stagedFailure").path("uploadId").asText(),
                tree(finished.getValue()).path("task").path("headers").path("X-Opaque-Id").asText(),
                "The server-side task must be identifiable by the upload token even when its submission ACK was lost");
            var successor = worker(target, secondProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            assertThrows(RfsException.class, successor::run);
            assertTrue(successor.source.readOffsets.isEmpty());
            assertEquals(0, publications(secondProxy));
            assertFalse(secondProxy.requests.stream().anyMatch(r -> r.path().endsWith("/_bulk")));
            assertEquals(0, readCheckpoint(checkpoint));
            assertEquals(tree(LARGE), source(cluster, index, "same"), "Fail closed: no later delete or blind replacement was issued");
            assertEquals(1, failures.records().size(), "Recovery must not attribute the prior document's failure to a different input");
            // An operator can identify the tagged task and attach its ID using a conditional journal write.
            String journalPath = "/" + index + "-staging/_doc/" + failed.path("stagedFailure").path("journalId").asText();
            var journal = tree(cluster.get(journalPath).getValue());
            var repaired = ((ObjectNode) journal.path("_source")).deepCopy().put("taskId", actualTask.get());
            assertEquals(200, cluster.put(journalPath + "?if_seq_no=" + journal.path("_seq_no").asLong()
                + "&if_primary_term=" + journal.path("_primary_term").asLong(), repaired.toString()).getKey());
            var reconciled = worker(target, secondProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            assertEquals(CompletionStatus.WORK_COMPLETED, reconciled.run());
            assertEquals(2, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(),
                "After reconciliation, checkpoint replay must finish with the later delete");
            assertBounds(firstProxy);
            assertBounds(secondProxy);
        }
    }

    private void recordsRealLateFieldMappingFailure(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "late-mapping-error";
        cluster.createIndex(index, """
            {"settings":{"number_of_replicas":0},"mappings":{"properties":{
                "early_field":{"type":"text","index":false},"late_field":{"type":"integer","coerce":false}}}}
            """);
        assertEquals(201, cluster.put("/" + index + "/_doc/bad", "{\"early_field\":\"old\",\"late_field\":1}").getKey());
        var before = source(cluster, index, "bad");
        String bad = "{\"early_field\":\"" + "x".repeat(40_000) + "\",\"late_field\":\"not-an-integer\"}";
        var documents = List.of(doc("bad", bad, 0), doc("later", "{\"early_field\":\"later\",\"late_field\":2}", 1));
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            assertThrows(RfsException.class,
                worker(target, proxy, index, documents, checkpoint(index), null, new FailedRequestsLogger(), failures)::run);
            assertEquals(before, source(cluster, index, "bad"));
            assertEquals(404, cluster.get("/" + index + "/_doc/later").getKey());
            var failed = failures.records().get(0);
            assertEquals("mapper_parsing_exception", failed.path("failureType").asText());
            assertEquals("NON_RETRYABLE", failed.path("failureClass").asText());
            assertEquals("PUBLICATION_RESULT", failed.path("stagedFailure").path("stage").asText());
            assertFalse(failed.path("stagedFailure").path("publicationMayHaveCommitted").asBoolean());
            assertEquals(bad, failed.path("transformedRequestBody").asText().split("\n")[1]);
            assertTrue(failed.path("stagedFailure").path("responseBody").asText().contains("late_field"));
            assertTrue(tree(failed.path("stagedFailure").path("requestBody").asText()).has("script"));
            assertTrue(proxy.requests.stream().filter(r -> r.path().contains("/_update/")).count() > 3);
            assertBounds(proxy);
        }
    }

    private void preservesOrderAcrossMixedBulkItemStatuses(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "mixed-item-status";
        cluster.createIndex(index, "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"value\":{\"type\":\"integer\",\"coerce\":false}}}}");
        var documents = List.of(doc("same", "{\"value\":1}", 0), doc("bad", "{\"value\":\"invalid\"}", 1),
            doc("good", "{\"value\":3}", 2), delete("same", 3), doc("last", "{\"value\":4}", 4));
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var loseFirstItemAcknowledgement = new AtomicBoolean(true);
            proxy.afterForward = (request, response) -> {
                if (request.path().endsWith("/_bulk") && loseFirstItemAcknowledgement.compareAndSet(true, false)) {
                    var result = (ObjectNode) tree(response.body());
                    assertEquals(3, result.path("items").size(), "Cut before the dependent delete even though the bytes fit");
                    var first = (ObjectNode) result.path("items").get(0).path("index");
                    first.remove(List.of("result", "_version", "_seq_no", "_primary_term", "_shards"));
                    first.put("status", 429).set("error", tree("{\"type\":\"es_rejected_execution_exception\",\"reason\":\"lost item ACK\"}"));
                    result.put("errors", true);
                    return new SizeLimitingProxy.Response(200, bytes(result.toString()));
                }
                return response;
            };
            var checkpoint = checkpoint(index);
            var worker = worker(target, proxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures, 100);
            assertEquals(CompletionStatus.WORK_COMPLETED, worker.run());
            assertEquals(5, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "An earlier index retry cannot resurrect a later delete");
            assertEquals(tree("{\"value\":3}"), source(cluster, index, "good"));
            assertEquals(tree("{\"value\":4}"), source(cluster, index, "last"));
            assertEquals(404, cluster.get("/" + index + "/_doc/bad").getKey());
            assertEquals(1, failures.records().size());
            assertEquals("bad", failures.records().get(0).path("documentId").asText());
            assertTrue(failures.records().get(0).path("stagedFailure").isNull(), "Normal failures stay attached to their own document");
            var bulks = proxy.requests.stream().filter(r -> r.path().endsWith("/_bulk")).toList();
            assertEquals(3, bulks.size(), "Initial distinct IDs, retry of one ID, then its dependent delete");
            assertEquals("same", tree(text(bulks.get(1).body()).split("\n")[0]).path("index").path("_id").asText());
            assertArrayEquals(bytes("{\"value\":1}"), bytes(text(bulks.get(1).body()).split("\n")[1]));
            assertBounds(proxy);
        }
    }

    private void recordsEncodingFailureAfterEarlierChunksCommitted(
        SearchClusterContainer target, ClusterOperations cluster
    ) throws Exception {
        String index = "late-encoding-error";
        createIndex(cluster, index);
        assertEquals(201, cluster.put("/" + index + "/_doc/bad", "{\"value\":\"old\"}").getKey());
        String invalid = "{\"padding\":\"" + "x".repeat(40_000) + "\",\"duplicate\":1,\"duplicate\":2}";
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var checkpoint = checkpoint(index);
            assertThrows(RfsException.class, worker(target, proxy, index, List.of(doc("bad", invalid, 0)), checkpoint,
                null, new FailedRequestsLogger(), failures)::run);
            assertEquals(0, readCheckpoint(checkpoint));
            assertEquals(tree("{\"value\":\"old\"}"), source(cluster, index, "bad"));
            assertEquals(0, publications(proxy));
            var record = failures.records().get(0);
            assertEquals("NON_RETRYABLE", record.path("failureClass").asText());
            assertEquals(invalid, record.path("originalDocument").asText());
            assertEquals(invalid, record.path("transformedRequestBody").asText().split("\n")[1]);
            var detail = record.path("stagedFailure");
            assertEquals("ENCODE_CHUNK", detail.path("stage").asText());
            assertTrue(detail.path("errorMessage").asText().contains("Duplicate field"));
            assertTrue(detail.path("method").isNull(), "Encoding failed before a request for this chunk existed");
            assertTrue(source(cluster, index + "-staging", detail.path("uploadId").asText()).path("next").asLong() > 0);
            assertBounds(proxy);
        }
    }

    private Worker worker(
        SearchClusterContainer target, SizeLimitingProxy proxy, String index, List<Document> documents, Path checkpoint,
        BulkOperationTransformer transform, FailedRequestsLogger logger, FailureFiles failures
    ) {
        return worker(target, proxy, index, documents, checkpoint, transform, logger, failures, 1);
    }

    private Worker worker(
        SearchClusterContainer target, SizeLimitingProxy proxy, String index, List<Document> documents, Path checkpoint,
        BulkOperationTransformer transform, FailedRequestsLogger logger, FailureFiles failures, int batchSize
    ) {
        var transport = new RestClient(ConnectionContextTestParams.builder().host(proxy.url()).build().toConnectionContext());
        var client = new OpenSearchClient_OS_2_11(transport, logger, target.getContainerVersion().getVersion(),
            CompressionMode.UNCOMPRESSED, 100, LIMIT) {
            @Override
            protected Retry getBulkRetryStrategy() {
                return Retry.fixedDelay(2, Duration.ofMillis(10));
            }
        };
        client.setLargeDocumentStagingIndex(index + "-staging");
        failures.configure(client);
        var source = new RestartableSource(index, documents);
        var sink = new OpenSearchDocumentSink(client, transform == null ? null : () -> transform,
            false, DocumentExceptionAllowlist.empty(), null);
        assertTrue(sink.requiresOrderedWrites());
        var cursor = new AtomicReference<WorkItemCursor>();
        var bootstrap = DocumentMigrationBootstrap.builder().documentSource(source).targetClient(client)
            .maxDocsPerBatch(batchSize).maxBytesPerBatch(Long.MAX_VALUE).batchConcurrency(8)
            .cursorConsumer(progress -> {
                cursor.set(progress);
                try {
                    Files.writeString(checkpoint, Long.toString(progress.getProgressCheckpointNum()));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).build();
        return new Worker(bootstrap, new PipelineConfig(source, sink, batchSize, Long.MAX_VALUE, 8), source, checkpoint, cursor);
    }

    private record Worker(
        DocumentMigrationBootstrap bootstrap, PipelineConfig config, RestartableSource source,
        Path checkpoint, AtomicReference<WorkItemCursor> cursor
    ) {
        CompletionStatus run() {
            var work = new IWorkCoordinator.WorkItemAndDuration(Instant.now().plusSeconds(120),
                new IWorkCoordinator.WorkItemAndDuration.WorkItem(source.index, 0, readCheckpoint(checkpoint)));
            try (var context = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext()) {
                return bootstrap.runPartitionMigration(work, config, context);
            }
        }
    }

    private static final class RestartableSource implements DocumentSource {
        final String index;
        final List<Document> documents;
        final List<Long> readOffsets = new CopyOnWriteArrayList<>();

        RestartableSource(String index, List<Document> documents) {
            this.index = index;
            this.documents = documents;
        }

        @Override public List<String> listCollections() { return List.of(index); }
        @Override public List<Partition> listPartitions(String collection) { return List.of(new EsShardPartition("snapshot", index, 0)); }
        @Override public CollectionMetadata readCollectionMetadata(String collection) { throw new UnsupportedOperationException(); }
        @Override public Flux<Document> readDocuments(Partition partition, long offset) {
            return Flux.defer(() -> {
                readOffsets.add(offset);
                return Flux.fromIterable(documents).filter(doc -> doc.position().orElseThrow() >= offset);
            });
        }
    }

    private static final class FailureFiles implements AutoCloseable {
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<S3FailedDocumentStreamSink> sinks = new CopyOnWriteArrayList<>();
        final AtomicInteger workers = new AtomicInteger();
        final CompletableFuture<Void> durable;
        final CountDownLatch started;
        final String session = "recovery-" + UUID.randomUUID();

        FailureFiles() { this(CompletableFuture.completedFuture(null), new CountDownLatch(0)); }

        FailureFiles(CompletableFuture<Void> durable, CountDownLatch started) {
            this.durable = durable;
            this.started = started;
        }

        void configure(OpenSearchClient client) {
            String worker = "worker-" + workers.incrementAndGet();
            var sdkUploader = S3FailedDocumentStreamSink.s3ClientUploader(s3);
            var sink = S3FailedDocumentStreamSink.builder().bucket(BUCKET).prefix("failed/")
                .sessionId(session).workerId(worker).region(S3_CONTAINER.getRegion())
                .uploader((bucket, key, file) -> {
                    keys.add(key);
                    started.countDown();
                    return durable.thenCompose(ignored -> sdkUploader.upload(bucket, key, file));
                }).build();
            sinks.add(sink);
            client.setFailedDocumentStreamContext(sink, session, worker);
        }

        List<JsonNode> records() throws IOException {
            var records = new ArrayList<JsonNode>();
            for (var key : keys) {
                var upload = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                    AsyncResponseTransformer.toBytes()).join().asByteArray();
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(upload))) {
                    for (String line : text(gzip.readAllBytes()).split("\n")) {
                        if (!line.isBlank()) records.add(tree(line));
                    }
                }
            }
            return records;
        }

        @Override public void close() { sinks.forEach(S3FailedDocumentStreamSink::close); }
    }

    private Path checkpoint(String name) throws IOException {
        var path = directory.resolve(name + "-checkpoint");
        Files.writeString(path, "0");
        return path;
    }

    private static long readCheckpoint(Path path) {
        try {
            return Long.parseLong(Files.readString(path));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Document doc(String id, String json, long position) {
        return new Document(id, bytes(json), Document.Operation.UPSERT, Map.of(), Map.of(Document.SOURCE_META_POSITION, position));
    }

    private static Document delete(String id, long position) {
        return new Document(id, null, Document.Operation.DELETE, Map.of(), Map.of(Document.SOURCE_META_POSITION, position));
    }

    private static void createIndex(ClusterOperations cluster, String index) {
        cluster.createIndex(index, "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"enabled\":false}}");
    }

    private static JsonNode source(ClusterOperations cluster, String index, String id) {
        var response = cluster.get("/" + index + "/_doc/" + id);
        assertEquals(200, response.getKey(), response.getValue());
        return tree(response.getValue()).path("_source");
    }

    private static JsonNode tree(byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static JsonNode tree(String json) { return tree(bytes(json)); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static SizeLimitingProxy.Response response(int status, String body) { return new SizeLimitingProxy.Response(status, bytes(body)); }
    private static long publications(SizeLimitingProxy proxy) { return proxy.requests.stream().filter(r -> r.path().startsWith("/_reindex")).count(); }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Test did not release the delayed request");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertBounds(SizeLimitingProxy proxy) {
        assertNull(proxy.failure.get());
        assertFalse(proxy.requests.isEmpty());
        for (var request : proxy.requests) {
            assertTrue(request.wireBody().length <= LIMIT, request.path());
            assertTrue(request.body().length <= LIMIT, request.path());
        }
    }
}
