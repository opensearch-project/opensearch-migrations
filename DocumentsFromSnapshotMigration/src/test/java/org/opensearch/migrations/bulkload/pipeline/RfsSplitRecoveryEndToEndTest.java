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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real OpenSearch, the production bootstrap/checkpoint path, real gzip failed-document files, and
 * controlled network failures. Each restart constructs a new client, sink, source and bootstrap
 * using the checkpoint persisted by its predecessor. This source emits dependent index/delete
 * operations and requests ordering through the same source contract as delta snapshots.
 */
@Tag("isolatedTest")
class RfsSplitRecoveryEndToEndTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int LIMIT = 8192;
    private static final String BUCKET = "rfs-split-recovery";
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
            for (String phase : List.of("INDEX", "PART", "COMPLETE")) {
                fencesDelayedPredecessor(target, cluster, phase, false);
                fencesDelayedPredecessor(target, cluster, phase, true);
            }
            resumesAfterLostCompletionAcknowledgement(target, cluster);
            recordsRealLateFieldMappingFailure(target, cluster);
            recordsEncodingFailureAfterEarlierChunksCommitted(target, cluster);
            rejectsDuplicateKeysAfterEarlierChunksCommitted(target, cluster);
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
            proxy.afterForward = (request, response) -> request.method().equals("PUT") && request.path().contains("/_doc/" + targetId)
                && response.status() == 200 && loseCreateAcknowledgement.compareAndSet(true, false) ? null : response;
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
            assertFalse(failedRun.isDone(), "A split failure must await durable DLQ flush before reporting the batch error");
            assertEquals(1, readCheckpoint(checkpoint), "Only the earlier complete document may checkpoint");
            assertTrue(source(cluster, index, targetId).has("_rfs_large_document"), "Assembly is visible in the destination document");
            assertFalse(source(cluster, index, targetId).has("obsolete"), "The initial index replaces pre-existing partial source");
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
            assertEquals("PART", record.path("splitFailure").path("stage").asText());
            assertTrue(record.path("splitFailure").path("partSequence").asLong() >= 2);
            assertFalse(record.path("splitFailure").path("completionMayHaveCommitted").asBoolean());
            assertEquals(text(rejected.get().body()), record.path("splitFailure").path("requestBody").asText());
            var partialState = source(cluster, index, targetId).path("_rfs_large_document");
            assertTrue(partialState.path("next").asLong() > 0, "Several parts really committed before the later part failed");
            assertEquals(record.path("splitFailure").path("attemptId"), partialState.path("attempt"));
            assertEquals(1, logs.getCapturedLogEvents().size());
            var logged = logs.getCapturedLogEvents().get(0).getContextData();
            assertEquals(record.path("transformedRequestBody").asText(), logged.get("requestBody"));
            assertEquals(record.path("originalDocument").asText(), logged.get("originalDocument"));
            assertEquals(record.path("splitFailure"), tree(logged.get("splitFailure")));
            assertFalse(loseCreateAcknowledgement.get(), "The initial create really committed before its response was lost");
            assertEquals(1, proxy.requests.stream().filter(r -> r.method().equals("PUT")
                && r.path().contains("/_doc/" + targetId)).count(), "A lost initial ACK must be read back without repeating the replacement");

            proxy.intercept = request -> null;
            var second = worker(target, proxy, sourceIndex, documents, checkpoint, transform, new FailedRequestsLogger(), failures);
            assertEquals(CompletionStatus.WORK_COMPLETED, second.run());
            assertEquals(List.of(1L), second.source.readOffsets, "The successor must read from the persisted cursor");
            assertEquals(3, readCheckpoint(checkpoint));
            assertEquals(tree(LARGE), source(cluster, index, targetId));
            assertFalse(source(cluster, index, targetId).has("obsolete"), "Replay fully replaces a pre-existing partial document");
            assertEquals(tree("{\"value\":3}"), source(cluster, index, "later"));
            assertEquals(1, failures.records().size());
            assertTrue(second.cursor.get().isShardComplete());
            assertBounds(proxy);
        } finally {
            durable.complete(null);
        }
    }

    private void fencesDelayedPredecessor(
        SearchClusterContainer target, ClusterOperations cluster, String phase, boolean deleteAfter
    ) throws Exception {
        String index = "fence-" + phase.toLowerCase(java.util.Locale.ROOT) + (deleteAfter ? "-deleted" : "-present");
        createIndex(cluster, index);
        assertEquals(201, cluster.put("/" + index + "/_doc/same", "{\"obsolete\":true}").getKey());
        var documents = deleteAfter
            ? List.of(doc("same", LARGE, 0), delete("same", 1), doc("later", "{\"value\":2}", 2))
            : List.of(doc("same", LARGE, 0), doc("later", "{\"value\":2}", 1));
        var checkpoint = checkpoint(index);
        try (var firstProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var secondProxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var delayed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var held = new AtomicReference<SizeLimitingProxy.Request>();
            firstProxy.beforeForward = request -> {
                boolean match = switch (phase) {
                    case "INDEX" -> request.method().equals("PUT") && request.path().contains("/_doc/same");
                    case "PART" -> request.path().contains("/_update/")
                        && tree(request.body()).path("script").path("params").path("seq").asLong(-1) == 2;
                    case "COMPLETE" -> request.completesDocument();
                    default -> throw new IllegalArgumentException(phase);
                };
                if (match && held.compareAndSet(null, request)) {
                    delayed.countDown();
                    await(release);
                }
            };
            var predecessor = worker(target, firstProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            var predecessorRun = CompletableFuture.supplyAsync(predecessor::run);
            try {
                assertTrue(delayed.await(30, TimeUnit.SECONDS));
                assertEquals(0, readCheckpoint(checkpoint));
                var successor = worker(target, secondProxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
                assertEquals(CompletionStatus.WORK_COMPLETED, successor.run());
                assertEquals(documents.size(), readCheckpoint(checkpoint));
                assertEquals(deleteAfter ? 404 : 200, cluster.get("/" + index + "/_doc/same").getKey());
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> predecessorRun.get(30, TimeUnit.SECONDS));
            assertTrue(held.get().path().contains("if_seq_no="));
            int rejectionStatus = deleteAfter && !phase.equals("INDEX") ? 404 : 409;
            assertTrue(firstProxy.completedRequests.stream().anyMatch(r -> r.request().equals(held.get())
                && r.response().status() == rejectionStatus), "Reject stale writes: updates to deleted documents return 404; revision conflicts return 409");
            if (deleteAfter) {
                assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "A late predecessor cannot resurrect the successor's delete");
            } else {
                assertEquals(tree(LARGE), source(cluster, index, "same"), "A late predecessor cannot change the successor's complete source");
            }
            assertEquals(tree("{\"value\":2}"), source(cluster, index, "later"));
            assertEquals(documents.size(), readCheckpoint(checkpoint));
            assertEquals(phase, failures.records().get(0).path("splitFailure").path("stage").asText());
            assertBounds(firstProxy);
            assertBounds(secondProxy);
        }
    }

    private void resumesAfterLostCompletionAcknowledgement(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "lost-completion";
        createIndex(cluster, index);
        var checkpoint = checkpoint(index);
        var documents = List.of(doc("same", LARGE, 0), delete("same", 1), doc("later", "{\"value\":3}", 2));
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var lose = new AtomicBoolean(true);
            proxy.afterForward = (request, response) -> request.completesDocument() && response.status() == 200
                && lose.compareAndSet(true, false) ? null : response;
            assertThrows(RfsException.class,
                worker(target, proxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures)::run);
            assertFalse(lose.get());
            assertEquals(0, readCheckpoint(checkpoint));
            assertEquals(tree(LARGE), source(cluster, index, "same"), "Completion committed, but its ACK was lost");
            assertEquals(404, cluster.get("/" + index + "/_doc/later").getKey());
            assertEquals(1, publications(proxy), "Do not blindly retry the final source replacement");
            var failed = failures.records().get(0);
            assertEquals("COMPLETE", failed.path("splitFailure").path("stage").asText());
            assertTrue(failed.path("splitFailure").path("completionMayHaveCommitted").asBoolean());
            assertEquals(LARGE, failed.path("transformedRequestBody").asText().split("\n")[1]);
            var successor = worker(target, proxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures);
            assertEquals(CompletionStatus.WORK_COMPLETED, successor.run());
            assertEquals(List.of(0L), successor.source.readOffsets);
            assertEquals(3, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey());
            assertEquals(tree("{\"value\":3}"), source(cluster, index, "later"));
            assertEquals(2, publications(proxy));
            assertBounds(proxy);
        }
    }

    private void recordsRealLateFieldMappingFailure(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "late-mapping-error";
        cluster.createIndex(index, """
            {"settings":{"number_of_replicas":0},"mappings":{"properties":{
                "early_field":{"type":"text","index":false},"late_field":{"type":"integer","coerce":false}}}}
            """);
        assertEquals(201, cluster.put("/" + index + "/_doc/bad", "{\"early_field\":\"old\",\"late_field\":1}").getKey());
        String bad = "{\"early_field\":\"" + "x".repeat(40_000) + "\",\"late_field\":\"not-an-integer\"}";
        var documents = List.of(doc("bad", bad, 0), doc("later", "{\"early_field\":\"later\",\"late_field\":2}", 1));
        var checkpoint = checkpoint(index);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            assertThrows(RfsException.class,
                worker(target, proxy, index, documents, checkpoint, null, new FailedRequestsLogger(), failures)::run);
            assertTrue(source(cluster, index, "bad").path("_rfs_large_document").path("next").asLong() > 3);
            assertEquals(0, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/later").getKey());
            var failed = failures.records().get(0);
            assertEquals("mapper_parsing_exception", failed.path("failureType").asText());
            assertEquals("NON_RETRYABLE", failed.path("failureClass").asText());
            assertEquals("COMPLETE", failed.path("splitFailure").path("stage").asText());
            assertFalse(failed.path("splitFailure").path("completionMayHaveCommitted").asBoolean());
            assertEquals(bad, failed.path("transformedRequestBody").asText().split("\n")[1]);
            assertTrue(failed.path("splitFailure").path("responseBody").asText().contains("late_field"));
            assertTrue(tree(failed.path("splitFailure").path("requestBody").asText()).has("script"));
            assertTrue(proxy.requests.stream().filter(r -> r.path().contains("/_update/")).count() > 3);
            String repaired = bad.replace("\"not-an-integer\"", "17");
            BulkOperationTransformer repair = operations -> {
                operations.forEach(op -> {
                    if ("bad".equals(op.getOperation().getId())) op.setRawDocument(bytes(repaired));
                });
                return operations;
            };
            assertEquals(CompletionStatus.WORK_COMPLETED,
                worker(target, proxy, index, documents, checkpoint, repair, new FailedRequestsLogger(), failures).run());
            assertEquals(2, readCheckpoint(checkpoint));
            assertEquals(tree(repaired), source(cluster, index, "bad"));
            assertEquals(1, failures.records().size());
            assertBounds(proxy);
        }
    }

    private void preservesOrderAcrossMixedBulkItemStatuses(SearchClusterContainer target, ClusterOperations cluster) throws Exception {
        String index = "mixed-item-status";
        cluster.createIndex(index, "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"value\":{\"type\":\"integer\",\"coerce\":false}}}}");
        String alias = index + "-alias";
        assertEquals(200, cluster.put("/" + index + "/_alias/" + alias, "{}").getKey());
        BulkOperationTransformer useAliasForIndex = operations -> {
            operations.forEach(op -> {
                if (op instanceof IndexOp indexOp && "same".equals(op.getOperation().getId())) {
                    indexOp.getOperation().setIndex(alias);
                }
            });
            return operations;
        };
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
            var worker = worker(target, proxy, index, documents, checkpoint, useAliasForIndex, new FailedRequestsLogger(), failures, 100);
            assertEquals(CompletionStatus.WORK_COMPLETED, worker.run());
            assertEquals(5, readCheckpoint(checkpoint));
            assertEquals(404, cluster.get("/" + index + "/_doc/same").getKey(), "An earlier index retry cannot resurrect a later delete");
            assertEquals(tree("{\"value\":3}"), source(cluster, index, "good"));
            assertEquals(tree("{\"value\":4}"), source(cluster, index, "last"));
            assertEquals(404, cluster.get("/" + index + "/_doc/bad").getKey());
            assertEquals(1, failures.records().size());
            assertEquals("bad", failures.records().get(0).path("documentId").asText());
            assertTrue(failures.records().get(0).path("splitFailure").isNull(), "Normal failures stay attached to their own document");
            var bulks = proxy.requests.stream().filter(r -> r.path().endsWith("/_bulk")).toList();
            assertEquals(3, bulks.size(), "Initial distinct IDs, retry of one ID, then its dependent delete");
            assertEquals("same", tree(text(bulks.get(1).body()).split("\n")[0]).path("index").path("_id").asText());
            assertEquals(alias, tree(text(bulks.get(1).body()).split("\n")[0]).path("index").path("_index").asText());
            assertEquals(index, tree(text(bulks.get(2).body()).split("\n")[0]).path("delete").path("_index").asText(),
                "The dependent delete names the concrete index while its predecessor names an alias");
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
        String invalid = "{\"padding\":\"" + "x".repeat(40_000) + "\",\"value\":1} {}";
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var checkpoint = checkpoint(index);
            assertThrows(RfsException.class, worker(target, proxy, index, List.of(doc("bad", invalid, 0)), checkpoint,
                null, new FailedRequestsLogger(), failures)::run);
            assertEquals(0, readCheckpoint(checkpoint));
            assertTrue(source(cluster, index, "bad").has("_rfs_large_document"));
            assertEquals(0, publications(proxy));
            var record = failures.records().get(0);
            assertEquals("NON_RETRYABLE", record.path("failureClass").asText());
            assertEquals(invalid, record.path("originalDocument").asText());
            assertEquals(invalid, record.path("transformedRequestBody").asText().split("\n")[1]);
            var detail = record.path("splitFailure");
            assertEquals("ENCODE_PART", detail.path("stage").asText());
            assertTrue(detail.path("errorMessage").asText().contains("exactly one JSON object"));
            assertTrue(detail.path("method").isNull(), "Encoding failed before a request for this chunk existed");
            assertTrue(source(cluster, index, "bad").path("_rfs_large_document").path("next").asLong() > 0);
            assertBounds(proxy);
        }
    }

    private void rejectsDuplicateKeysAfterEarlierChunksCommitted(
        SearchClusterContainer target, ClusterOperations cluster
    ) throws Exception {
        String index = "late-duplicate-key";
        createIndex(cluster, index);
        assertEquals(201, cluster.put("/" + index + "/_doc/bad", "{\"value\":\"old\"}").getKey());
        // Equivalent escaped names, and null as the first value, must still count as a duplicate.
        String duplicate = "{\"padding\":\"" + "x".repeat(40_000) + "\",\"duplicate\":null,\"dupli\\u0063ate\":2}";
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var failures = new FailureFiles()) {
            var checkpoint = checkpoint(index);
            assertThrows(RfsException.class, worker(target, proxy, index, List.of(doc("bad", duplicate, 0)), checkpoint,
                null, new FailedRequestsLogger(), failures)::run);
            assertEquals(0, readCheckpoint(checkpoint));
            assertTrue(source(cluster, index, "bad").has("_rfs_large_document"));
            assertEquals(0, publications(proxy));
            assertEquals(1, failures.records().size());
            var record = failures.records().get(0);
            assertEquals("NON_RETRYABLE", record.path("failureClass").asText());
            assertEquals(duplicate, record.path("originalDocument").asText());
            assertEquals(duplicate, record.path("transformedRequestBody").asText().split("\n")[1]);
            var detail = record.path("splitFailure");
            assertEquals("PART", detail.path("stage").asText());
            assertTrue(detail.path("responseBody").asText().contains("Duplicate field"));
            assertEquals("POST", detail.path("method").asText());
            assertTrue(detail.path("requestBody").asText().contains("duplicate"));
            assertTrue(source(cluster, index, "bad").path("_rfs_large_document").path("next").asLong() > 0);
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
        client.setSplitLargeDocuments(true);
        failures.configure(client);
        var source = new RestartableSource(index, documents);
        var sink = new OpenSearchDocumentSink(client, transform == null ? null : () -> transform,
            false, DocumentExceptionAllowlist.empty(), null);
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
        @Override public boolean requiresOrderedWrites() { return true; }
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
    private static long publications(SizeLimitingProxy proxy) { return proxy.requests.stream().filter(SizeLimitingProxy.Request::completesDocument).count(); }

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
        assertTrue(proxy.requests.stream().noneMatch(r -> r.path().contains("staging") || r.path().startsWith("/_reindex")
            || r.path().startsWith("/_tasks")), "Use only the destination index and document");
        for (var request : proxy.requests) {
            assertTrue(request.wireBody().length <= LIMIT, request.path());
            assertTrue(request.body().length <= LIMIT, request.path());
        }
    }
}
