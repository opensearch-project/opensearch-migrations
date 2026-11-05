package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.DeleteOp;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.IndexOpType;
import org.opensearch.migrations.bulkload.common.bulk.enums.VersionType;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SplitDocumentWriterTest {
    private static final String TARGET_INDEX = "{\"target\":{\"mappings\":{},\"settings\":{}}}";
    @ParameterizedTest
    @ValueSource(strings = {"create", "version", "delete"})
    void legacyOversizedSingletonsPreserveTheirOperationSemantics(String kind) {
        var metadata = IndexOperationMeta.builder().index("target").id("id").build();
        if (kind.equals("create")) {
            metadata.setOpType(IndexOpType.CREATE);
        } else if (kind.equals("version")) {
            metadata.setVersioning(VersionControlMetadata.builder().version(12L).versionType(VersionType.EXTERNAL_GTE).build());
        }
        BulkOperationSpec operation = kind.equals("delete")
            ? DeleteOp.builder().operation(DeleteOperationMeta.builder().index("target").id("id").build()).build()
            : IndexOp.builder().operation(metadata).document(Map.of("text", "x".repeat(1000))).build();
        byte[] expected = BulkNdjson.toBulkNdjsonBytes(List.of(operation), ObjectMapperFactory.createDefaultMapper());
        var transport = mock(RestClient.class);
        when(transport.postAsyncBytes(any(), any(), any(), any())).thenAnswer(call -> {
            assertArrayEquals(expected, call.getArgument(1));
            return Mono.just(response(200, "{\"errors\":false,\"items\":[]}"));
        });
        var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.19"), CompressionMode.UNCOMPRESSED, 100, expected.length - 1L);
        client.sendBulkRequest("target", List.of(operation), null).block();
        verify(transport).postAsyncBytes(any(), any(), any(), any());
        verify(transport, never()).asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitDocumentCeilingCannotBeBypassedBySplittingAndRetainsFullDiagnostics(boolean splitting) {
        var transport = mock(RestClient.class);
        var logger = mock(FailedRequestsLogger.class);
        var client = new OpenSearchClient_OS_2_11(transport, logger,
            Version.fromString("OS 2.19"), CompressionMode.UNCOMPRESSED, 100, 4096);
        assertThrows(IllegalArgumentException.class, () -> client.setMaxDocumentSizeBytes(0));
        assertThrows(IllegalArgumentException.class, () -> client.setMaxDocumentSizeBytes(-1));
        client.setMaxDocumentSizeBytes(5000);
        if (splitting) {
            client.setSplitLargeDocuments(true);
        }
        byte[] original = "{\"before\":1}".getBytes(StandardCharsets.UTF_8);
        var op = IndexOp.builder().operation(IndexOperationMeta.builder().index("renamed").id("new-id").build())
            .document(Map.of("expanded", "雪".repeat(3000))).originalSourceBytes(original).build();
        var records = mock(FailedDocumentStreamSink.class);
        var flush = Sinks.<Void>empty();
        when(records.write(any())).thenReturn(Mono.empty());
        when(records.flush()).thenReturn(flush.asMono());
        client.setFailedDocumentStreamContext(records, "session", "worker");
        var failed = client.sendBulkRequest("default-index", List.of(op), null).toFuture();
        assertFalse(failed.isDone(), "The failure must await DLQ durability");
        var capture = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
        verify(records).write(capture.capture());
        var record = capture.getValue();
        assertEquals("renamed", record.getTargetIndex());
        assertEquals("new-id", record.getDocumentId());
        assertEquals(FailureClass.NON_RETRYABLE, record.getFailureClass());
        assertEquals(new String(original, StandardCharsets.UTF_8), record.getOriginalDocument());
        assertEquals(new String(BulkNdjson.toBulkNdjsonBytes(List.of(op), ObjectMapperFactory.createDefaultMapper()),
            StandardCharsets.UTF_8), record.getTransformedRequestBody());
        assertNull(record.getSplitFailure(), "No split or upload was attempted");
        verify(transport, never()).postAsyncBytes(any(), any(), any(), any());
        verify(logger).logLargeDocumentFailure(org.mockito.ArgumentMatchers.eq("renamed"),
            org.mockito.ArgumentMatchers.eq(record.getTransformedRequestBody()),
            org.mockito.ArgumentMatchers.eq(record.getOriginalDocument()),
            org.mockito.ArgumentMatchers.isNull(), any(OversizedDocumentException.class));
        flush.tryEmitEmpty();
        assertThrows(java.util.concurrent.CompletionException.class, failed::join);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestsNoNextPartWhileRetryingAndStopsGenerationOnFailureOrCancellation(boolean cancel) throws Exception {
        var transport = mock(RestClient.class);
        var firstAck = Sinks.<HttpResponse>one();
        var secondAck = Sinks.<HttpResponse>one();
        var retryStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var requests = new CopyOnWriteArrayList<byte[]>();
        var state = new java.util.concurrent.atomic.AtomicReference<String>();
        when(transport.asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any())).thenAnswer(call -> {
            String path = call.getArgument(1);
            HttpMethod method = call.getArgument(0);
            if (path.equals("target")) return Mono.just(response(200, TARGET_INDEX));
            if (path.endsWith("/_mapping")) return Mono.just(response(200, "{\"acknowledged\":true}"));
            if (method.equals(HttpMethod.GET)) {
                return Mono.just(state.get() == null ? response(404, "{\"found\":false}")
                    : response(200, "{\"found\":true,\"_seq_no\":0,\"_primary_term\":1,\"_source\":" + state.get() + "}"));
            }
            if (method.equals(HttpMethod.PUT)) {
                state.set(new String(call.<byte[]>getArgument(2), StandardCharsets.UTF_8));
                assertTrue(path.contains("op_type=create"));
                return Mono.just(response(201, "{\"_seq_no\":0,\"_primary_term\":1,\"result\":\"created\"}"));
            }
            requests.add(call.getArgument(2));
            if (requests.size() <= 2) assertTrue(path.contains("if_seq_no=0&if_primary_term=1"));
            if (requests.size() == 1) return Mono.error(new java.io.IOException("ACK lost before the request applied"));
            if (requests.size() == 2) {
                retryStarted.countDown();
                return firstAck.asMono();
            }
            assertTrue(path.contains("if_seq_no=1&if_primary_term=1"));
            secondStarted.countDown();
            return secondAck.asMono();
        });
        var produced = new AtomicInteger();
        OperationSplitter splitter = (operation, limit) -> JsonDocumentChunks.split(operation, limit)
            .doOnNext(part -> produced.incrementAndGet());
        var operation = BulkNdjson.encode(IndexOp.builder()
            .operation(IndexOperationMeta.builder().index("target").id("id").build())
            .document(Map.of("text", "x".repeat(50_000))).build(), ObjectMapperFactory.createDefaultMapper());
        var future = new SplitDocumentWriter(transport, 4096, splitter).write("target", operation, null).toFuture();
        try {
            assertTrue(retryStarted.await(10, TimeUnit.SECONDS));
            assertEquals(1, produced.get(), "Retry must retain one part without reading the next");
            assertEquals(2, requests.size());
            assertSame(requests.get(0), requests.get(1), "Retry must reuse the measured bytes");
            firstAck.tryEmitValue(response(200, "{\"_seq_no\":1,\"_primary_term\":1,\"result\":\"updated\"}"));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertEquals(2, produced.get());
            if (cancel) {
                future.cancel(true);
                assertTrue(future.isCancelled());
            } else {
                secondAck.tryEmitValue(response(400, "{\"error\":{\"type\":\"mapper_parsing_exception\"}}"));
                assertThrows(java.util.concurrent.CompletionException.class, future::join);
            }
            assertEquals(2, produced.get());
            assertEquals(3, requests.size());
        } finally {
            future.cancel(true);
        }
    }

    private static HttpResponse response(int status, String body) {
        return new HttpResponse(status, "", Map.of(), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "sequence", "create", "type", "generated-id", "delete", "update"})
    void rejectsUnsupportedSemanticsBeforeAnyHttpRequest(String kind) {
        var transport = mock(RestClient.class);
        var metadata = IndexOperationMeta.builder().id("id").index("target").build();
        switch (kind) {
            case "version" -> metadata.setVersioning(VersionControlMetadata.builder()
                .version(10L).versionType(VersionType.EXTERNAL_GTE).build());
            case "sequence" -> metadata.setVersioning(VersionControlMetadata.builder().ifSeqNo(3L).ifPrimaryTerm(1L).build());
            case "create" -> metadata.setOpType(IndexOpType.CREATE);
            case "type" -> metadata.setType("_doc");
            case "generated-id" -> metadata.setId(null);
            default -> { }
        }
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var operation = BulkNdjson.encode(IndexOp.builder().operation(metadata)
            .document(Map.of("text", "x".repeat(10_000))).build(), mapper);
        if (kind.equals("delete")) {
            operation = BulkNdjson.encode(DeleteOp.builder()
                .operation(DeleteOperationMeta.builder().index("target").id("x".repeat(10_000)).build()).build(), mapper);
        } else if (kind.equals("update")) {
            operation = new BulkNdjson.EncodedOperation("{\"update\":{\"_index\":\"target\",\"_id\":\"id\"}}"
                .getBytes(StandardCharsets.UTF_8), operation.body(), false);
        }
        var encoded = operation;
        assertThrows(OversizedDocumentException.class,
            () -> new SplitDocumentWriter(transport, 4096).write("target", encoded, null).block());
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsSplittingOnServerlessAndElasticsearch() {
        for (String version : List.of("AOSS 2.0", "ES 7.10")) {
            var transport = mock(RestClient.class);
            var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
                Version.fromString(version), CompressionMode.UNCOMPRESSED, 100, 4096);
            assertThrows(IllegalArgumentException.class, () -> client.setSplitLargeDocuments(true));
            verifyNoInteractions(transport);
        }
    }

    @Test
    void exactFitsRemainBoundedEvenWhenGzipWouldExpandThem() {
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var op = IndexOp.builder().operation(IndexOperationMeta.builder().index("i").id("a").build())
            .document(Map.of()).build();
        byte[] original = BulkNdjson.toBulkNdjsonBytes(List.of(op), mapper);
        var transport = mock(RestClient.class);
        when(transport.postAsyncBytes(any(), any(), any(), any())).thenAnswer(call -> {
            byte[] body = call.getArgument(1);
            Map<String, List<String>> headers = call.getArgument(2);
            assertTrue(body.length <= original.length);
            assertArrayEquals(original, body);
            assertFalse(headers.containsKey("content-encoding"), "Gzip overhead must not break an exact fit");
            return Mono.just(new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}"));
        });
        var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.19"), CompressionMode.GZIP_BODY_COMPRESSION, 100, original.length);
        client.sendBulkRequest("i", List.of(op), null).block();
    }

    @ParameterizedTest
    @ValueSource(strings = {"default_pipeline", "final_pipeline", "disabled-source", "source-includes", "source-excludes",
        "derived-source", "async-durability", "reserved-field"})
    void rejectsUnsupportedTargetConfigurationBeforeAnyDocumentWrite(String kind) throws Exception {
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var index = mapper.createObjectNode();
        var mappings = index.putObject("mappings");
        var settings = index.putObject("settings").putObject("index");
        switch (kind) {
            case "default_pipeline", "final_pipeline" -> settings.put(kind, "pipeline");
            case "disabled-source" -> mappings.putObject("_source").put("enabled", false);
            case "source-includes" -> mappings.putObject("_source").putArray("includes").add("only-this");
            case "source-excludes" -> mappings.putObject("_source").putArray("excludes").add("something");
            case "derived-source" -> settings.putObject("derived_source").put("enabled", true);
            case "async-durability" -> settings.putObject("translog").put("durability", "async");
            case "reserved-field" -> mappings.putObject("properties").putObject(SplitDocumentWriter.STATE_FIELD).put("type", "keyword");
            default -> throw new IllegalArgumentException(kind);
        }
        var transport = mock(RestClient.class);
        when(transport.asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertEquals(HttpMethod.GET, call.getArgument(0), "Reject before changing mappings or writing a document");
            assertEquals("target", call.getArgument(1));
            return Mono.just(response(200, mapper.writeValueAsString(Map.of("target", index))));
        });
        var operation = BulkNdjson.encode(IndexOp.builder()
            .operation(IndexOperationMeta.builder().index("target").id("id").build())
            .document(Map.of("text", "x".repeat(10_000))).build(), mapper);
        assertThrows(SplitDocumentException.class,
            () -> new SplitDocumentWriter(transport, 4096).write("target", operation, null).block());
        verify(transport).asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any());
    }
}
