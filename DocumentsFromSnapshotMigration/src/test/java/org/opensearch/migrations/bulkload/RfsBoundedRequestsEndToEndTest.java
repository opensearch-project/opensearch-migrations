package org.opensearch.migrations.bulkload;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OversizedDocumentException;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.SplitDocumentException;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.metadata.WriteMetadata;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SizeLimitingProxy;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.transformers.BulkOperationTransformer;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.util.retry.Retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Lucene snapshot -> RFS transformations -> size-enforcing HTTP proxy -> OpenSearch with the
 * same HTTP limit. Assertions compare every stored source, not just document counts or mock calls.
 */
@Tag("isolatedTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RfsBoundedRequestsEndToEndTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int LIMIT = 8192;
    private static final String SNAPSHOT = "bounded-snapshot";
    private static final String SOURCE = "bounded-source";
    private static final String SMALL_SOURCE = "bounded-small";
    private static final String ROUTING = "tenant-路由";
    private static final String EXPANSION = "é 雪 😀 \" \\ \n".repeat(1500);
    @TempDir static Path directory;

    private final Map<String, Map<String, Object>> original = new LinkedHashMap<>();
    private SnapshotExtractor extractor;

    static Stream<ContainerVersion> targets() {
        return Stream.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_LATEST);
    }

    @BeforeAll
    void createRealSnapshot() throws Exception {
        original.put("first", document(0, Map.of("small", "first")));
        var complex = new LinkedHashMap<String, Object>();
        complex.put("unicode", "é雪😀\"\\\n\r\t\u0000".repeat(3000));
        complex.put("key.雪😀".repeat(1500), "a field name larger than the request limit");
        complex.put("nested", Arrays.asList(null, true, false, Map.of("array", List.of(1, 2, 3)),
            List.of(), Map.of(), "", Long.MAX_VALUE, Long.MIN_VALUE, 9007199254740993L, 1.25, 1e100));
        complex.put("array", java.util.stream.IntStream.range(0, 3000).boxed().toList());
        original.put("large", document(1, complex));
        original.put("last", document(2, Map.of("small", "last")));
        try (var source = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            source.start();
            var operations = new ClusterOperations(source);
            var sourceTransport = new RestClient(ConnectionContextTestParams.builder()
                .host(source.getUrl()).build().toConnectionContext());
            for (var index : List.of(SOURCE, SMALL_SOURCE)) {
                operations.createIndex(index, """
                    {"settings":{"number_of_shards":1,"number_of_replicas":0},
                     "mappings":{"enabled":false,"_routing":{"required":true}}}
                    """);
            }
            for (var entry : original.entrySet()) {
                var response = sourceTransport.put(SOURCE + "/_doc/" + entry.getKey()
                    + "?routing=" + URLEncoder.encode(ROUTING, StandardCharsets.UTF_8),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry.getValue()), null);
                assertEquals(201, response.statusCode, response.body);
                var stored = MAPPER.readTree(get(operations, SOURCE, entry.getKey()).getValue());
                assertEquals(MAPPER.readTree(MAPPER.writeValueAsBytes(entry.getValue())), stored.path("_source"));
                assertEquals(ROUTING, stored.path("_routing").asText());
            }
            for (int i = 0; i < 12; i++) {
                operations.createDocument(SMALL_SOURCE, "d" + i,
                    MAPPER.writeValueAsString(document(i, Map.of())),
                    URLEncoder.encode(ROUTING, StandardCharsets.UTF_8), null);
            }
            operations.refresh(SOURCE);
            operations.refresh(SMALL_SOURCE);
            operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "bounded-repo");
            operations.takeSnapshot("bounded-repo", SNAPSHOT, "bounded-*");
            source.copySnapshotData(directory.resolve("snapshot").toString());
        }
        extractor = SnapshotExtractor.forLocalSnapshot(directory.resolve("snapshot"),
            SearchClusterContainer.OS_V2_19_4.getVersion());
    }

    @ParameterizedTest(name = "snapshot, transformations, retries and bounded HTTP on {0}")
    @MethodSource("targets")
    void verifiesBothOrdinaryBatchingAndLargerThanRequestDocuments(ContainerVersion targetVersion) throws Exception {
        try (var target = new SearchClusterContainer(targetVersion)
            .withEnv("http.max_content_length", LIMIT + "b")) {
            target.start();
            var operations = new ClusterOperations(target);
            createPipelines(operations);
            operations.createIndex("direct-proof", "{\"mappings\":{\"enabled\":false}}");
            assertEquals(413, operations.put("/direct-proof/_doc/large",
                MAPPER.writeValueAsString(original.get("large"))).getKey(),
                "The real target must reject a direct oversized document");

            for (String path : List.of("raw", "native", "json")) {
                verifyLargeDocuments(target, operations, path);
            }
            verifyOrdinaryTransformedBatches(target, operations);
            verifyLegacySingletonsAndIndependentDocumentCeiling(target, operations);
            verifyCompletionFailureDoesNotCheckpoint(target, operations);
            verifyMixedIndexAndDeleteOperations(target, operations);
            verifyLargeVectorThroughAlias(target, operations);
            verifyExactNumericValuesWithoutAnIngestPipeline(target, operations);
            verifyMappingFailureRetainsRecoverablePartialDocument(target, operations);
            verifyMalformedCompletionIsNotAcknowledged(target, operations);
        }
    }

    private void verifyLegacySingletonsAndIndependentDocumentCeiling(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        int batchTarget = 4096;
        String largeSource = "{\"value\":\"" + "é".repeat(2500) + "\"}";
        for (String path : List.of("raw", "native", "json")) {
            String index = "legacy-singleton-" + path;
            operations.createIndex(index, "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"enabled\":false}}");
            try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
                var client = client(target, proxy, batchTarget);
                IJsonTransformer transform = switch (path) {
                    case "raw" -> null;
                    case "native" -> (BulkOperationTransformer) ops -> ops;
                    default -> input -> input;
                };
                var sink = new OpenSearchDocumentSink(client, transform == null ? null : () -> transform,
                    false, DocumentExceptionAllowlist.empty(), null);
                var docs = List.of(
                    new Document("first", "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of()),
                    new Document("large", largeSource.getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of()),
                    new Document("last", "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of()));
                var result = sink.writeBatch(index, docs).block(Duration.ofSeconds(20));
                assertNotNull(result);
                assertEquals(3, result.target().upserts());
                assertEquals(MAPPER.readTree(largeSource),
                    MAPPER.readTree(operations.get("/" + index + "/_doc/large").getValue()).path("_source"));
                var bulks = proxy.requests.stream().filter(r -> r.path().endsWith("/_bulk")).toList();
                assertEquals(3, bulks.size());
                assertTrue(bulks.get(1).body().length > batchTarget);
                assertEquals(2, new String(bulks.get(1).body(), StandardCharsets.UTF_8).split("\n").length);
                assertFalse(proxy.requests.stream().anyMatch(r -> r.path().startsWith("/_reindex")));

                int sent = proxy.requests.size();
                client.setMaxDocumentSizeBytes(batchTarget);
                assertThrows(OversizedDocumentException.class,
                    () -> sink.writeBatch(index, List.of(docs.get(1))).block(Duration.ofSeconds(20)));
                assertEquals(sent, proxy.requests.size(), "An explicit document ceiling must reject before transmission");

                client.setMaxDocumentSizeBytes(Long.MAX_VALUE);
                client.setSplitLargeDocuments(true);
                int stagedStart = proxy.requests.size();
                var staged = sink.writeBatch(index, List.of(docs.get(1))).block(Duration.ofMinutes(1));
                assertNotNull(staged);
                assertTrue(proxy.requests.subList(stagedStart, proxy.requests.size()).stream()
                    .allMatch(r -> r.body().length <= batchTarget && r.wireBody().length <= batchTarget));
                assertTrue(proxy.requests.stream().anyMatch(SizeLimitingProxy.Request::completesDocument));
                assertEquals(MAPPER.readTree(largeSource),
                    MAPPER.readTree(operations.get("/" + index + "/_doc/large").getValue()).path("_source"));
                assertBounds(proxy);
            }
        }
    }

    @Test
    void migratesDocumentAboveDefaultBulkLimitWithBoundedRequests() throws Exception {
        int requestLimit = 1024 * 1024;
        String text = "é雪😀-" + "x".repeat(25 * 1024 * 1024) + "-雪😀é";
        byte[] transformed = MAPPER.writeValueAsBytes(Map.of("text", text));
        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_LATEST)
            // Keep the real circuit breaker enabled; this scale case needs more heap than the 1 GiB fixture default.
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms2g -Xmx2g")
            .withEnv("http.max_content_length", requestLimit + "b")) {
            target.start();
            var operations = new ClusterOperations(target);
            operations.createIndex("large-absolute-size", "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"enabled\":false}}");
            try (var proxy = new SizeLimitingProxy(target.getUrl(), requestLimit)) {
                var client = client(target, proxy, requestLimit);
                client.setSplitLargeDocuments(true);
                BulkOperationTransformer expand = ops -> {
                    ops.forEach(op -> op.setRawDocument(transformed));
                    return ops;
                };
                var result = new OpenSearchDocumentSink(client, () -> expand, false, DocumentExceptionAllowlist.empty(), null)
                    .writeBatch("large-absolute-size", List.of(new Document("large", "{}".getBytes(StandardCharsets.UTF_8),
                        Document.Operation.UPSERT, Map.of(), Map.of())))
                    .block(Duration.ofMinutes(3));
                assertNotNull(result);
                assertEquals(2, result.bytesInBatch(), "The source was only two bytes before transformation");
                assertEquals(transformed.length, result.target().bytes());
                assertEquals(1, result.target().upserts());
                var response = operations.get("/large-absolute-size/_doc/large");
                assertEquals(200, response.getKey());
                assertTrue(text.equals(MAPPER.readTree(response.getValue()).path("_source").path("text").asText()),
                    "The entire 25 MiB string must match, including both Unicode ends");
                assertBounds(proxy, requestLimit);
                assertTrue(proxy.requests.stream().filter(r -> r.path().contains("/_update/")).count() > 25);
            }
        }
    }

    private void verifyLargeDocuments(SearchClusterContainer target, ClusterOperations operations, String path) throws Exception {
        String index = "large-" + path;
        createTarget(operations, index);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT,
                 directory.resolve(target.getContainerVersion().getVersion().toString() + "-" + index)).build()) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            proxy.loseNextUpdateAcknowledgement.set(true);
            IJsonTransformer transformer = transformer(path, index, EXPANSION);
            var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                false, DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, 1, 1);
            List<ProgressCursor> cursors = new CopyOnWriteArrayList<>();
            var publicationStarted = new CountDownLatch(1);
            var releasePublication = new CountDownLatch(1);
            proxy.beforeForward = request -> {
                if (request.completesDocument() && publicationStarted.getCount() != 0) {
                    publicationStarted.countDown();
                    try {
                        if (!releasePublication.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release publication");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            };
            var future = pipeline.migratePartition(source.listPartitions(SOURCE).get(0),
                path.equals("raw") ? index : "unused-default", 0).doOnNext(cursors::add).collectList().toFuture();
            try {
                assertTrue(publicationStarted.await(60, TimeUnit.SECONDS));
                assertFalse(future.isDone());
                assertTrue(cursors.isEmpty(), "A partially uploaded document cannot advance a source checkpoint");
                String firstLargeId = path.equals("raw") ? "large" : path + "-first";
                var partial = get(operations, index, firstLargeId);
                assertEquals(200, partial.getKey(), "Assembly takes place directly in the destination document");
                assertTrue(MAPPER.readTree(partial.getValue()).path("_source").has("_rfs_large_document"));
            } finally {
                releasePublication.countDown();
            }
            future.get(120, TimeUnit.SECONDS);
            assertEquals(3, cursors.stream().mapToLong(ProgressCursor::docsInBatch).sum());
            assertEquals(3, cursors.get(cursors.size() - 1).nextPosition());
            assertEquals(3, cursors.stream().mapToLong(c -> c.metrics().target().upserts()).sum());
            for (var entry : original.entrySet()) {
                var expected = new LinkedHashMap<>(entry.getValue());
                if (!path.equals("raw")) {
                    expected.put("text", expected.get("text") + EXPANSION);
                }
                var response = get(operations, index, path.equals("raw") ? entry.getKey() : path + "-" + entry.getKey());
                assertEquals(200, response.getKey(), response.getValue());
                var actual = MAPPER.readTree(response.getValue());
                assertEquals(MAPPER.readTree(MAPPER.writeValueAsBytes(expected)), actual.path("_source"), path + "/" + entry.getKey());
                assertEquals(ROUTING, actual.path("_routing").asText());
            }
            operations.refresh(index);
            assertEquals(3, count(operations, index));
            assertBounds(proxy);
            var updates = proxy.requests.stream().filter(r -> r.path().contains("/_update/")).toList();
            assertTrue(updates.size() > 2);
            assertFalse(proxy.loseNextUpdateAcknowledgement.get(), "Lose an ACK after the actual server applies a part");
            assertTrue(proxy.requests.stream().anyMatch(r -> r.path().contains("_source_includes=")),
                "Read back the attempt token and part sequence to resolve a lost ACK");
        }
    }

    private void verifyOrdinaryTransformedBatches(SearchClusterContainer target, ClusterOperations operations) throws Exception {
        for (String path : List.of("native", "json")) {
            String index = "ordinary-" + path;
            createTarget(operations, index);
            assertEquals(200, operations.put("/" + index + "/_settings",
                "{\"index.default_pipeline\":\"bounded-default\",\"index.final_pipeline\":\"bounded-final\"}").getKey());
            try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
                 var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT,
                     directory.resolve(target.getContainerVersion().getVersion().toString() + "-" + index)).build()) {
                var transform = transformer(path, index, "é雪😀 ".repeat(280));
                var sink = new OpenSearchDocumentSink(client(target, proxy), () -> transform,
                    false, DocumentExceptionAllowlist.empty(), null);
                var cursors = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, 1, 1)
                    .migratePartition(source.listPartitions(SMALL_SOURCE).get(0), "unused-default", 0)
                    .collectList().block(Duration.ofSeconds(60));
                assertNotNull(cursors);
                assertEquals(12, cursors.stream().mapToLong(ProgressCursor::docsInBatch).sum());
                assertTrue(proxy.requests.size() > 1, "Post-transform bytes, not the small source bytes, force splitting");
                assertTrue(proxy.requests.stream().allMatch(r -> r.path().endsWith("/_bulk")));
                assertBounds(proxy);
                for (int i = 0; i < 12; i++) {
                    var expected = document(i, Map.of());
                    expected.put("text", expected.get("text") + "é雪😀 ".repeat(280));
                    expected.put("default_runs", 1);
                    expected.put("final_runs", 1);
                    var response = get(operations, index, path + "-d" + i);
                    assertEquals(200, response.getKey());
                    assertEquals(MAPPER.readTree(MAPPER.writeValueAsBytes(expected)), MAPPER.readTree(response.getValue()).path("_source"));
                }
            }
        }
    }

    private void verifyCompletionFailureDoesNotCheckpoint(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        String index = "completion-failure";
        createTarget(operations, index);
        operations.createDocument(index, "large", MAPPER.writeValueAsString(document(-1, Map.of("previous", true))),
            URLEncoder.encode(ROUTING, StandardCharsets.UTF_8), null);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT);
             var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT,
                 directory.resolve(target.getContainerVersion().getVersion().toString() + "-failure")).build()) {
            proxy.rejectCompletion.set(true);
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
            List<ProgressCursor> cursors = new ArrayList<>();
            assertThrows(RuntimeException.class, () -> new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, 1, 1)
                .migratePartition(source.listPartitions(SOURCE).get(0), index, 0).doOnNext(cursors::add)
                .blockLast(Duration.ofSeconds(60)));
            assertTrue(cursors.isEmpty());
            assertTrue(MAPPER.readTree(get(operations, index, "large").getValue()).path("_source").has("_rfs_large_document"));
            assertEquals(404, get(operations, index, "last").getKey());
            assertEquals(1, proxy.requests.stream().filter(SizeLimitingProxy.Request::completesDocument).count(),
                "Completion must not be retried while its outcome might be unknown");
            assertBounds(proxy);
        }
    }

    private void verifyMixedIndexAndDeleteOperations(SearchClusterContainer target, ClusterOperations operations) throws Exception {
        String index = "mixed-operations";
        createTarget(operations, index);
        byte[] large = MAPPER.writeValueAsBytes(original.get("large"));
        byte[] small = MAPPER.writeValueAsBytes(original.get("first"));
        var hints = Map.of(Document.HINT_ROUTING, ROUTING);
        var documents = List.of(
            new Document("a", small, Document.Operation.UPSERT, hints, Map.of()),
            new Document("a", large, Document.Operation.DELETE, hints, Map.of()),
            new Document("b", large, Document.Operation.UPSERT, hints, Map.of()),
            new Document("b", large, Document.Operation.DELETE, hints, Map.of()),
            new Document("c", small, Document.Operation.UPSERT, hints, Map.of()));
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            var result = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null)
                .writeBatch(index, documents).block(Duration.ofSeconds(60));
            assertNotNull(result);
            assertEquals(3, result.target().upserts());
            assertEquals(2, result.target().deletes());
            assertEquals(404, get(operations, index, "a").getKey());
            assertEquals(404, get(operations, index, "b").getKey());
            assertEquals(200, get(operations, index, "c").getKey());
            assertBounds(proxy);
            for (var request : proxy.requests) {
                if (!request.path().endsWith("/_bulk")) {
                    continue;
                }
                String ndjson = new String(request.body(), StandardCharsets.UTF_8);
                assertTrue(ndjson.endsWith("\n"));
                String[] lines = ndjson.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    var action = MAPPER.readTree(lines[i]);
                    assertEquals(1, action.size());
                    if (action.has("index")) {
                        assertTrue(MAPPER.readTree(lines[++i]).isObject());
                    } else {
                        assertTrue(action.has("delete"), "Deletes must remain one-line operations");
                    }
                }
            }
        }
    }

    private static OpenSearchClient client(SearchClusterContainer target, SizeLimitingProxy proxy) {
        return client(target, proxy, LIMIT);
    }

    private static OpenSearchClient client(SearchClusterContainer target, SizeLimitingProxy proxy, int limit) {
        var connection = ConnectionContextTestParams.builder().host(proxy.url()).build().toConnectionContext();
        return new OpenSearchClient_OS_2_11(new RestClient(connection), new FailedRequestsLogger(),
            target.getContainerVersion().getVersion(), CompressionMode.GZIP_BODY_COMPRESSION, 100, limit) {
            @Override
            protected Retry getBulkRetryStrategy() {
                return Retry.max(0);
            }
        };
    }

    private void verifyLargeVectorThroughAlias(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        String index = "large-vector";
        createTarget(operations, index, 4096);
        assertEquals(200, operations.put("/" + index + "/_alias/bounded-write-alias", "{}").getKey());
        var document = document(1, Map.of());
        document.put("vector", java.util.Collections.nCopies(4096, 1.25));
        byte[] body = MAPPER.writeValueAsBytes(document);
        assertTrue(body.length > LIMIT, "The vector array alone must exceed a request");
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            BulkOperationTransformer transform = operationsToWrite -> {
                for (var operation : operationsToWrite) {
                    ((IndexOp) operation).getOperation().setWrite(WriteMetadata.builder()
                        .requireAlias(true).build());
                }
                return operationsToWrite;
            };
            var result = new OpenSearchDocumentSink(client, () -> transform,
                false, DocumentExceptionAllowlist.empty(), null)
                .writeBatch("bounded-write-alias", List.of(new Document("vector", body, Document.Operation.UPSERT,
                    Map.of(Document.HINT_ROUTING, ROUTING), Map.of())))
                .block(Duration.ofSeconds(60));
            assertNotNull(result);
            assertEquals(1, result.target().upserts());
            var response = get(operations, index, "vector");
            assertEquals(200, response.getKey(), response.getValue());
            assertEquals(MAPPER.readTree(MAPPER.writeValueAsBytes(document)), MAPPER.readTree(response.getValue()).path("_source"));
            assertTrue(proxy.requests.stream().filter(r -> r.path().contains("/_update/"))
                .allMatch(r -> r.path().startsWith("/" + index + "/")), "Pin every part to the resolved concrete index");
            assertBounds(proxy);
        }
    }

    private void verifyMappingFailureRetainsRecoverablePartialDocument(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        String index = "mapping-failure";
        createTarget(operations, index, 9);
        var previous = document(1, Map.of("previous", true));
        previous.put("vector", java.util.Collections.nCopies(9, 1.0));
        operations.createDocument(index, "large", MAPPER.writeValueAsString(previous),
            URLEncoder.encode(ROUTING, StandardCharsets.UTF_8), null);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
            var document = new Document("large", MAPPER.writeValueAsBytes(original.get("large")), Document.Operation.UPSERT,
                Map.of(Document.HINT_ROUTING, ROUTING), Map.of());
            var error = assertThrows(SplitDocumentException.class,
                () -> sink.writeBatch(index, List.of(document)).block(Duration.ofSeconds(60)));
            assertEquals(400, error.getFailure().responseStatus());
            assertEquals("COMPLETE", error.getFailure().stage());
            assertTrue(error.getFailure().responseBody().contains("vector"));
            var partial = MAPPER.readTree(get(operations, index, "large").getValue()).path("_source");
            assertTrue(partial.has("_rfs_large_document"));
            assertFalse(partial.has("payload"), "Replay's first index removes obsolete fields");
            assertEquals(1, proxy.requests.stream().filter(SizeLimitingProxy.Request::completesDocument).count());
            assertBounds(proxy);
        }
    }

    private void verifyExactNumericValuesWithoutAnIngestPipeline(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        String index = "numeric-precision";
        operations.createIndex(index, "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"enabled\":false}}");
        var exact = ObjectMapperFactory.createDefaultMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        String json = "{\"fraction\":0.123456789012345678901234567890123456789,"
            + "\"huge\":9876543210987654321098765432109876543210,"
            + "\"array\":[-9223372036854775808,1e100,-0.0,0.000000000000000000000001234567890123456789],"
            + "\"nested\":{\"number\":1.234567890123456789012345678901},"
            + "\"document\":{\"next\":9.8765432109876543210987654321},"
            + "\"numbers\":[0.123456789012345678901,\"literal\"],"
            + "\"stack\":\"user value\",\"a.b\":{\"\":3.141592653589793238462643383279},"
            + "\"padding\":\"" + "x".repeat(30_000) + "\"}";
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null)
                .writeBatch(index, List.of(new Document("number", json.getBytes(StandardCharsets.UTF_8),
                    Document.Operation.UPSERT, Map.of(), Map.of()))).block(Duration.ofSeconds(60));
            var response = operations.get("/" + index + "/_doc/number");
            assertEquals(200, response.getKey(), response.getValue());
            assertEquals(exact.readTree(json), exact.readTree(response.getValue()).path("_source"),
                "Compare exact decimal/integer values, not rounded doubles");
            assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(MAPPER.readTree(response.getValue()).path("_source").path("array").get(2).asDouble()));
            assertBounds(proxy);
        }
    }

    private void verifyMalformedCompletionIsNotAcknowledged(
        SearchClusterContainer target, ClusterOperations operations
    ) throws Exception {
        String index = "malformed-completion";
        createTarget(operations, index);
        try (var proxy = new SizeLimitingProxy(target.getUrl(), LIMIT)) {
            var client = client(target, proxy);
            client.setSplitLargeDocuments(true);
            proxy.intercept = request -> request.completesDocument()
                ? new SizeLimitingProxy.Response(200, "{}".getBytes(StandardCharsets.UTF_8)) : null;
            var document = new Document("large", MAPPER.writeValueAsBytes(original.get("large")), Document.Operation.UPSERT,
                Map.of(Document.HINT_ROUTING, ROUTING), Map.of());
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
            var error = assertThrows(SplitDocumentException.class,
                () -> sink.writeBatch(index, List.of(document)).block(Duration.ofSeconds(60)));
            assertEquals("COMPLETE", error.getFailure().stage());
            assertTrue(MAPPER.readTree(get(operations, index, "large").getValue()).path("_source").has("_rfs_large_document"));
            assertBounds(proxy);
        }
    }

    @SuppressWarnings("unchecked")
    private static IJsonTransformer transformer(String path, String index, String expansion) {
        if (path.equals("raw")) {
            return null;
        }
        if (path.equals("native")) {
            return (BulkOperationTransformer) operations -> {
                for (var operation : operations) {
                    var op = (IndexOp) operation;
                    op.getOperation().setId(path + "-" + op.getOperation().getId());
                    op.getOperation().setIndex(index);
                    op.getDocument().put("text", op.getDocument().get("text") + expansion);
                }
                return operations;
            };
        }
        return input -> {
            var operations = (List<Map<String, Object>>) input;
            for (var op : operations) {
                var meta = (Map<String, Object>) op.get("operation");
                meta.put("_id", path + "-" + meta.get("_id"));
                meta.put("_index", index);
                var document = (Map<String, Object>) op.get("document");
                document.put("text", document.get("text") + expansion);
            }
            return operations;
        };
    }

    private static Map<String, Object> document(int ordinal, Map<String, ?> payload) {
        var document = new LinkedHashMap<String, Object>();
        document.put("ordinal", ordinal);
        document.put("text", "document " + ordinal);
        document.put("vector", List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0));
        document.put("payload", payload);
        return document;
    }

    private static void createPipelines(ClusterOperations operations) {
        for (String kind : List.of("default", "final", "custom")) {
            assertEquals(200, operations.put("/_ingest/pipeline/bounded-" + kind,
                "{\"processors\":[{\"script\":{\"source\":\"ctx." + kind + "_runs = (ctx."
                    + kind + "_runs == null ? 0 : ctx." + kind + "_runs) + 1\"}}]}").getKey());
        }
    }

    private static void createTarget(ClusterOperations operations, String index) {
        createTarget(operations, index, 8);
    }

    private static void createTarget(ClusterOperations operations, String index, int vectorDimension) {
        operations.createIndex(index, """
            {"settings":{"number_of_shards":2,"number_of_replicas":0,"index.knn":true},
             "mappings":{"dynamic":"strict","_routing":{"required":true},"properties":{
                "ordinal":{"type":"integer"},"text":{"type":"text"},
                "vector":{"type":"knn_vector","dimension":%d},"payload":{"type":"object","enabled":false},
                "default_runs":{"type":"integer"},"final_runs":{"type":"integer"},"custom_runs":{"type":"integer"}}}}
            """.formatted(vectorDimension));
    }

    private static Map.Entry<Integer, String> get(ClusterOperations operations, String index, String id) {
        return operations.get("/" + index + "/_doc/" + id + "?routing=" + URLEncoder.encode(ROUTING, StandardCharsets.UTF_8));
    }

    private static long count(ClusterOperations operations, String index) throws Exception {
        var response = operations.get("/" + index + "/_count");
        assertEquals(200, response.getKey(), response.getValue());
        return MAPPER.readTree(response.getValue()).path("count").asLong();
    }

    private static void assertBounds(SizeLimitingProxy proxy) {
        assertBounds(proxy, LIMIT);
    }

    private static void assertBounds(SizeLimitingProxy proxy, int limit) {
        assertFalse(proxy.requests.isEmpty());
        assertNull(proxy.failure.get(), "Proxy forwarding must succeed");
        assertTrue(proxy.requests.stream().noneMatch(r -> r.path().contains("staging") || r.path().startsWith("/_reindex")
            || r.path().startsWith("/_tasks")), "No staging index, reindex, or task API");
        for (var request : proxy.requests) {
            assertTrue(request.body().length <= limit, request.path() + ": uncompressed body exceeds cap");
            assertTrue(request.wireBody().length <= limit, request.path() + ": transmitted body exceeds cap");
        }
    }

}
