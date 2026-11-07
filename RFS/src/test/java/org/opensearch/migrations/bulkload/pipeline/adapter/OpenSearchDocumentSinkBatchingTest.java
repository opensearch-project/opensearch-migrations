package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.transformers.BulkOperationTransformer;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses the real serializer and client, recording the byte arrays passed to the HTTP transport. */
class OpenSearchDocumentSinkBatchingTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final HttpResponse OK = new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}");
    private final List<byte[]> requests = new CopyOnWriteArrayList<>();
    private final RestClient transport = mock(RestClient.class);

    private OpenSearchDocumentSink sink(IJsonTransformer transformer, int maxDocs, long maxBytes) {
        return sink(transformer, maxDocs, maxBytes, Retry.max(0));
    }

    private OpenSearchDocumentSink sink(IJsonTransformer transformer, int maxDocs, long maxBytes, Retry retry) {
        return sinkFromSupplier(transformer == null ? null : () -> transformer, maxDocs, maxBytes, retry);
    }

    private OpenSearchDocumentSink sinkFromSupplier(
        Supplier<IJsonTransformer> transformerSupplier, int maxDocs, long maxBytes, Retry retry
    ) {
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(transport.getConnectionContext()).thenReturn(connection);
        when(transport.postAsyncBytes(any(), any(), any(), any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            return Mono.just(OK);
        });
        var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, maxDocs, maxBytes) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return retry;
            }
        };
        return new OpenSearchDocumentSink(client, transformerSupplier,
            false, DocumentExceptionAllowlist.empty(), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "native", "json"})
    void countsMetadataUtf8AndNewlinesAndAllowsExactFits(String path) throws Exception {
        var docs = List.of(doc("é1", "{\"text\":\"é\"}"), doc("é2", "{\"text\":\"é\"}"), doc("é3", "{\"text\":\"é\"}"));
        var transformer = identity(path);
        long limit = serialized(docs.subList(0, 2)).length;
        var sink = sink(transformer, Integer.MAX_VALUE, limit);

        var result = sink.writeBatch("idx", docs).block(Duration.ofSeconds(10));

        assertNotNull(result);
        assertEquals(List.of(2, 1), requestCounts());
        assertEquals(limit, requests.get(0).length);
        assertTrue(requests.stream().allMatch(bytes -> bytes.length <= limit));
        assertEquals(List.of("é1", "é2", "é3"), sentIds());
        assertEquals(3, result.docsInBatch());
        assertEquals(3, result.target().upserts());
        assertEquals(docs.stream().mapToLong(Document::sourceLength).sum(), result.bytesInBatch());
        assertEquals(2, result.requests().total());
        assertEquals(2, result.requests().batches());
        assertEquals(requests.stream().mapToLong(bytes -> bytes.length).sum(), result.requests().bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "native", "json"})
    void splitsWhenOnlyTheFinalNewlineExceedsTheLimit(String path) throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":1}"));
        long limit = serialized(docs).length - 1L;

        sink(identity(path), Integer.MAX_VALUE, limit).writeBatch("idx", docs).block(Duration.ofSeconds(10));

        assertEquals(List.of(1, 1), requestCounts());
        assertTrue(requests.stream().allMatch(bytes -> bytes.length <= limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "native"})
    void multilineSourceUsesOneBodyLineAndExactByteLimits(String path) throws Exception {
        var source = "{\r\n\t\"text\":\"é雪😀\\n\\r\\t\\\"\",\n  \"value\":1e2,\r  \"values\":[1,\n2]\n}\r\n";
        var singleLine = source.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        var docs = List.of(doc("d1", source), doc("d2", source), doc("d3", source));
        var expected = serialized(List.of(doc("d1", singleLine), doc("d2", singleLine)));
        var result = sink(identity(path), Integer.MAX_VALUE, expected.length)
            .writeBatch("idx", docs).block(Duration.ofSeconds(10));

        assertEquals(List.of(2, 1), requestCounts());
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, requests.get(0));
        assertTrue(requests.stream().allMatch(bytes -> bytes.length <= expected.length));
        assertEquals(List.of("d1", "d2", "d3"), sentIds());
        for (var request : requests) {
            var lines = new String(request, StandardCharsets.UTF_8).split("\n");
            for (int i = 1; i < lines.length; i += 2) {
                assertEquals(singleLine, lines[i], "Preserve escapes and numeric spelling");
                assertEquals(MAPPER.readTree(source), MAPPER.readTree(lines[i]));
            }
        }
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length * 3L, result.bytesInBatch());
        assertEquals(singleLine.getBytes(StandardCharsets.UTF_8).length * 3L, result.target().bytes());
        assertEquals(source, new String(docs.get(0).source(), StandardCharsets.UTF_8),
            "The original source must remain intact for diagnostics");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\n\"text\":\"invalid\nstring\"}", "{\"text\":\"invalid\rstring\"}", "{\n\"value\":}"})
    void rejectsInvalidJsonBeforeNormalizingLineBreaks(String source) {
        assertThrows(IOException.class, () -> BulkNdjson.encodeRaw(doc("d1", source), "idx", false, MAPPER));
    }

    @Test
    void rawSourcePreparationValidatesBeforeEnteringTheSendPath() {
        var invalid = doc("d1", "{\"value\":}");
        var valid = doc("d1", "{\"value\":1}");
        var sink = sink(null, Integer.MAX_VALUE, 1024);

        var prepared = assertDoesNotThrow(() -> sink.prepareSourceDocument("idx", valid));

        assertEquals(serialized(List.of(valid)).length, prepared.sizeBytes());
        assertThrows(IOException.class, () -> sink.prepareSourceDocument("idx", invalid));
    }

    @Test
    void rawSourceSizingDoesNotRetainDocumentsInTheSink() throws Exception {
        var sink = sink(null, Integer.MAX_VALUE, 1024);

        sink.sourceBatchBytes("idx", doc("d1", "{\"value\":1}"));

        int retainedDocuments;
        try {
            var field = OpenSearchDocumentSink.class.getDeclaredField("preparedRawDocuments");
            field.setAccessible(true);
            retainedDocuments = ((Map<?, ?>) field.get(sink)).size();
        } catch (NoSuchFieldException e) {
            retainedDocuments = 0;
        }
        assertEquals(0, retainedDocuments, "Source sizing must carry preparation with the document");
    }

    @Test
    void rejectsUnescapedControlBytesInsideStrings() {
        for (char control = 0; control < ' '; control++) {
            var source = "{\n\"text\":\"before" + control + "after\"}";
            assertThrows(IOException.class, () -> BulkNdjson.encodeRaw(doc("d1", source), "idx", false, MAPPER),
                "Invalid control byte " + (int) control + " must not be replaced with a space");
        }
    }

    @Test
    void singleLineSourceRetainsOriginalBytes() {
        var document = doc("d1", "\t{\"text\":\"é雪😀\\n\\r\\t\"}\t");

        assertSame(document.source(), BulkNdjson.encodeRaw(document, "idx", false, MAPPER).body());
    }

    @Test
    void nineMiBDocumentsProduceOneDocumentPerRequestWithTenMiBLimit() throws Exception {
        var json = "{\"data\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}";
        var docs = List.of(doc("d1", json), doc("d2", json), doc("d3", json));
        long limit = 10L * 1024 * 1024;

        sink(null, Integer.MAX_VALUE, limit).writeBatch("idx", docs).block(Duration.ofSeconds(20));

        assertEquals(List.of(1, 1, 1), requestCounts());
        assertTrue(requests.stream().allMatch(bytes -> bytes.length <= limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "native", "json"})
    void isolatesOversizedOperationsAndRetainsOrder(String path) throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":\"" + "a".repeat(1000) + "\"}"),
            doc("d3", "{\"x\":1}"));
        long limit = 200;

        sink(identity(path), Integer.MAX_VALUE, limit).writeBatch("idx", docs).block(Duration.ofSeconds(10));

        assertEquals(List.of(1, 1, 1), requestCounts());
        assertTrue(requests.get(1).length > limit);
        assertEquals(List.of("d1", "d2", "d3"), sentIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void appliesByteLimitAfterTransformationWithoutReserializingBodies(boolean nativeTransform) throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":1}"), doc("d3", "{\"x\":1}"));
        IJsonTransformer transform = nativeTransform
            ? (BulkOperationTransformer) operations -> {
                operations.forEach(op -> op.setDocument(Map.of("expanded", "é".repeat(80))));
                return operations;
            }
            : input -> {
                @SuppressWarnings("unchecked")
                var operations = (List<Map<String, Object>>) input;
                operations.forEach(op -> op.put("document", Map.of("expanded", "é".repeat(80))));
                return operations;
            };
        long limit = 250;

        var result = sink(transform, Integer.MAX_VALUE, limit).writeBatch("idx", docs).block(Duration.ofSeconds(10));

        assertNotNull(result);
        assertEquals(List.of(1, 1, 1), requestCounts());
        assertTrue(requests.stream().allMatch(bytes -> bytes.length <= limit));
        assertEquals(3, result.target().upserts());
        assertEquals(3, result.docsInBatch());
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesDocumentLimitAfterOneToManyTransformation() throws Exception {
        IJsonTransformer expand = input -> {
            var original = ((List<Map<String, Object>>) input).get(0);
            return IntStream.range(0, 5).mapToObj(i -> {
                var copy = new HashMap<>(original);
                var metadata = new HashMap<>((Map<String, Object>) original.get("operation"));
                metadata.put("_id", "expanded-" + i);
                copy.put("operation", metadata);
                return copy;
            }).toList();
        };

        var result = sink(expand, 2, 1000).writeBatch("idx", List.of(doc("source", "{\"x\":1}"))).block();

        assertNotNull(result);
        assertEquals(List.of(2, 2, 1), requestCounts());
        assertEquals(1, result.docsInBatch());
        assertEquals(5, result.target().upserts());
        assertEquals(3, result.requests().total());
        assertEquals(IntStream.range(0, 5).mapToObj(i -> "expanded-" + i).toList(), sentIds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void repacksTransformedOutputAcrossSourceBatchesWithoutChangingTransformBatchSize() throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":1}"),
            doc("d3", "{\"x\":1}"), doc("d4", "{\"x\":1}"));
        var transformedBatchSizes = new CopyOnWriteArrayList<Integer>();
        IJsonTransformer transform = input -> {
            transformedBatchSizes.add(((List<Map<String, Object>>) input).size());
            return input;
        };
        var sink = sink(transform, Integer.MAX_VALUE, serialized(docs.subList(0, 2)).length);
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));

        var cursors = new DocumentMigrationPipeline(source, sink, 1, 1000)
            .migratePartition(partition, "idx", 0).collectList().block(Duration.ofSeconds(10));

        assertEquals(List.of(1, 1, 1, 1), transformedBatchSizes);
        assertEquals(List.of(2, 2), requestCounts());
        assertNotNull(cursors);
        assertEquals(1, cursors.size());
        assertEquals(4, cursors.get(0).nextPosition());
    }

    @Test
    void transformsSourceBatchesInParallelAndRetainsTheirOrder() throws Exception {
        var transformationsStarted = new CountDownLatch(2);
        var releaseTransformations = new CountDownLatch(1);
        var nextTransformer = new AtomicInteger();
        var sink = sinkFromSupplier(() -> {
            int transformerNumber = nextTransformer.getAndIncrement();
            return input -> {
                transformationsStarted.countDown();
                try {
                    if (!releaseTransformations.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release transformations");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                @SuppressWarnings("unchecked")
                var operations = (List<Map<String, Object>>) input;
                operations.forEach(operation -> operation.put("document",
                    Map.of("transformer", transformerNumber)));
                return operations;
            };
        }, Integer.MAX_VALUE, 1_000_000, Retry.max(0));
        var batches = List.of(
            List.of(doc("d1", "{\"x\":1}")),
            List.of(doc("d2", "{\"x\":1}"))
        );

        var result = CompletableFuture.supplyAsync(() ->
            sink.writeBatchGroup("idx", batches, 2).block(Duration.ofSeconds(10)));
        boolean transformationsOverlapped = transformationsStarted.await(1, TimeUnit.SECONDS);
        releaseTransformations.countDown();
        assertNotNull(result.get(10, TimeUnit.SECONDS));

        assertTrue(transformationsOverlapped);
        assertEquals(List.of("d1", "d2"), sentIds());
    }

    @Test
    void rawPipelineKeepsAtMostOnePartialRequestPerPersistentLane() {
        var fixture = rawBatchFixture();
        int totalDocs = fixture.sourceDocsPerBatch() * 16;
        var docs = fixture.documents().subList(0, totalDocs);
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));

        new DocumentMigrationPipeline(source, sink(null, Integer.MAX_VALUE, fixture.requestLimit()),
            Integer.MAX_VALUE, fixture.requestLimit(), 1, 10)
            .migratePartition(partition, "idx", 0).blockLast(Duration.ofSeconds(20));

        var counts = requestCounts();
        assertEquals(totalDocs, counts.stream().mapToInt(Integer::intValue).sum());
        assertTrue(counts.stream().allMatch(count -> count >= 1 && count <= 3),
            "Every request must respect the exact request limit: " + counts);
        long partiallyFilled = counts.stream().filter(count -> count < 3).count();
        int persistentLanes = Math.min(10, Math.max(1, Runtime.getRuntime().availableProcessors()));
        assertTrue(partiallyFilled <= persistentLanes,
            "Each persistent lane may flush at most one partially filled request: " + counts);
    }

    @Test
    void rawPipelineRefillsConcurrencyWithoutWaitingForARequestGroupTail() throws Exception {
        var docs = IntStream.range(0, 16)
            .mapToObj(i -> doc("d%03d".formatted(i), "{\"x\":\"" + "x".repeat(200) + "\"}"))
            .toList();
        long requestLimit = serialized(docs.subList(0, 1)).length;
        assertTrue(2L * docs.get(0).sourceLength() > requestLimit,
            "The source pipeline must make one source batch per document");
        var completions = IntStream.range(0, docs.size()).mapToObj(i -> Sinks.<HttpResponse>one()).toList();
        var starts = IntStream.range(0, docs.size()).mapToObj(i -> new CountDownLatch(1)).toList();
        var requestNumber = new AtomicInteger();
        var sink = sink(null, Integer.MAX_VALUE, requestLimit);
        doAnswer(invocation -> {
            int number = requestNumber.getAndIncrement();
            requests.add(invocation.getArgument(1));
            starts.get(number).countDown();
            return completions.get(number).asMono();
        }).when(transport).postAsyncBytes(any(), any(), any(), any());
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));
        var subscription = new DocumentMigrationPipeline(source, sink, Integer.MAX_VALUE, requestLimit, 1, 3)
            .migratePartition(partition, "idx", 0).subscribe();
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(starts.get(i).await(10, TimeUnit.SECONDS));
            }
            for (int i = 0; i < 6; i++) {
                completions.get(i).tryEmitValue(OK);
                assertTrue(starts.get(i + 3).await(1, TimeUnit.SECONDS),
                    "Completing request " + (i + 1) + " did not immediately start request " + (i + 4));
            }
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void splitRequestsCompleteInOrderBeforeTheSourceCheckpointAdvances() throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":1}"));
        var sink = sink(null, Integer.MAX_VALUE, serialized(docs).length - 1L);
        var completions = List.of(Sinks.<HttpResponse>one(), Sinks.<HttpResponse>one());
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            firstStarted.countDown();
            if (requests.size() == 2) {
                secondStarted.countDown();
            }
            return completions.get(requests.size() - 1).asMono();
        }).when(transport).postAsyncBytes(any(), any(), any(), any());
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));
        var cursors = new CopyOnWriteArrayList<ProgressCursor>();
        var future = new DocumentMigrationPipeline(source, sink, 10, 1000)
            .migratePartition(partition, "idx", 0).doOnNext(cursors::add).then().toFuture();
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        assertTrue(cursors.isEmpty());
        completions.get(0).tryEmitValue(OK);
        assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
        assertEquals(2, requests.size());
        assertTrue(cursors.isEmpty());
        completions.get(1).tryEmitValue(OK);
        future.get(10, TimeUnit.SECONDS);
        assertEquals(1, cursors.size());
        assertEquals(2, cursors.get(0).nextPosition());
        assertEquals(2, cursors.get(0).metrics().requests().total());
    }

    @Test
    void aPlainText429OnALaterSplitIsRetriedWithIdenticalBytesBeforeTheCheckpointAdvances() throws Exception {
        var docs = List.of(doc("d1", "{\"x\":1}"), doc("d2", "{\"x\":1}"));
        var sink = sink(null, Integer.MAX_VALUE, serialized(docs).length - 1L, Retry.fixedDelay(3, Duration.ofMillis(1)));
        var throttled = new HttpResponse(429, "Too Many Requests", Map.of("content-type", "text/plain"),
            "429 Too Many Requests");
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            return Mono.just(requests.size() == 2 ? throttled : OK);
        }).when(transport).postAsyncBytes(any(), any(), any(), any());
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));

        var cursors = new DocumentMigrationPipeline(source, sink, 10, 1000)
            .migratePartition(partition, "idx", 0).collectList().block(Duration.ofSeconds(10));

        assertEquals(3, requests.size());
        assertEquals(List.of("d1", "d2", "d2"), sentIds());
        org.junit.jupiter.api.Assertions.assertArrayEquals(requests.get(1), requests.get(2));
        assertEquals(1, cursors.size());
        assertEquals(2, cursors.get(0).nextPosition());
        assertEquals(3, cursors.get(0).metrics().requests().total());
        assertEquals(1, cursors.get(0).metrics().requests().retries());
    }

    @Test
    void rawSizeMeasurementDoesNotMaterializeTheBody() {
        var document = doc("d1", "{\"x\":\"é\"}");
        var operation = BulkOperationConverter.fromRawDocument(document, "idx");
        var encoded = BulkNdjson.encode(operation, MAPPER);
        assertSame(document.source(), operation.getRawDocument());
        assertSame(document.source(), encoded.body());
        assertEquals(serialized(List.of(document)).length, encoded.size());
    }

    @Test
    void transformedBodiesAreSerializedOnlyOnceForSizingAndRepeatedSending() {
        var serializations = new AtomicInteger();
        var value = new Object() {
            @JsonValue
            public String serializedValue() {
                serializations.incrementAndGet();
                return "é";
            }
        };
        var operation = IndexOp.builder()
            .operation(IndexOperationMeta.builder().id("d1").index("idx").build())
            .document(Map.of("value", value))
            .build();

        var encoded = BulkNdjson.encode(operation, MAPPER);
        assertEquals(encoded.size(), BulkNdjson.assemble(List.of(encoded)).bytes().length);
        assertEquals(encoded.size(), BulkNdjson.assemble(List.of(encoded)).bytes().length);
        assertEquals(1, serializations.get());
    }

    @Test
    void aFailedSplitRequestStopsLaterRequestsAndDoesNotAdvanceTheCheckpoint() {
        var sink = sink(null, 1, 1000);
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            return requests.size() == 1 ? Mono.just(OK) : Mono.error(new IllegalStateException("second request failed"));
        }).when(transport).postAsyncBytes(any(), any(), any(), any());
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        var docs = List.of(doc("d1", "{}"), doc("d2", "{}"), doc("d3", "{}"));
        when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));
        var cursors = new CopyOnWriteArrayList<ProgressCursor>();

        assertThrows(RuntimeException.class, () -> new DocumentMigrationPipeline(source, sink, 10, 1000)
            .migratePartition(partition, "idx", 0).doOnNext(cursors::add).blockLast(Duration.ofSeconds(10)));

        assertTrue(cursors.isEmpty());
        verify(transport, times(2)).postAsyncBytes(any(), any(), any(), any());
    }

    private static IJsonTransformer identity(String path) {
        return switch (path) {
            case "raw" -> null;
            case "native" -> (BulkOperationTransformer) operations -> operations;
            default -> input -> input;
        };
    }

    private static Document doc(String id, String json) {
        return new Document(id, json.getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(Document.HINT_ROUTING, "路由"), Map.of());
    }

    private static byte[] serialized(List<Document> docs) {
        return BulkNdjson.toRawNdjsonBytes(docs, "idx", false, MAPPER);
    }

    private static RawBatchFixture rawBatchFixture() {
        for (int padding = 50; padding <= 500; padding++) {
            int bodyPadding = padding;
            var documents = IntStream.range(0, 100)
                .mapToObj(i -> doc("d%03d".formatted(i), "{\"x\":\"" + "x".repeat(bodyPadding) + "\"}"))
                .toList();
            long requestLimit = serialized(documents.subList(0, 3)).length;
            int sourceDocsPerBatch = Math.toIntExact(requestLimit / documents.get(0).sourceLength());
            if (sourceDocsPerBatch > 3 && sourceDocsPerBatch * 8 % 3 != 0) {
                return new RawBatchFixture(documents, requestLimit, sourceDocsPerBatch);
            }
        }
        throw new AssertionError("Unable to construct the source/target batch-size fixture");
    }

    private record RawBatchFixture(List<Document> documents, long requestLimit, int sourceDocsPerBatch) {}

    private List<Integer> requestCounts() {
        return requests.stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8).split("\n").length / 2).toList();
    }

    private List<String> sentIds() throws Exception {
        var ids = new ArrayList<String>();
        for (var request : requests) {
            var lines = new String(request, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i += 2) {
                ids.add(MAPPER.readTree(lines[i]).path("index").path("_id").asText());
            }
        }
        return ids;
    }
}
