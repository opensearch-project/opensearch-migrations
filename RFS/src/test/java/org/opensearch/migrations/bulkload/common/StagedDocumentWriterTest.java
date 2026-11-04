package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
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

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StagedDocumentWriterTest {
    @ParameterizedTest
    @ValueSource(strings = {"missing", "string", "fractional", "negative", "overflow", "empty", "two", "noop", "timeout", "failures"})
    void refusesAmbiguousOrMalformedTaskSuccessCounters(String kind) throws Exception {
        var task = ObjectMapperFactory.createDefaultMapper().readTree("""
            {"completed":true,"response":{"timed_out":false,"total":1,"created":1,"updated":0,
                "deleted":0,"version_conflicts":0,"noops":0,"failures":[]}}
            """);
        assertTrue(StagedDocumentWriter.successfulPublication(task));
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) task.path("response");
        switch (kind) {
            case "missing" -> result.remove("noops");
            case "string" -> result.put("created", "1");
            case "fractional" -> result.put("created", 1.5);
            case "negative" -> result.put("created", -1);
            case "overflow" -> result.put("created", new java.math.BigInteger("18446744073709551617"));
            case "empty" -> result.put("total", 0);
            case "two" -> result.put("updated", 1);
            case "noop" -> result.put("noops", 1);
            case "timeout" -> result.put("timed_out", true);
            case "failures" -> result.putArray("failures").addObject().put("status", 400);
            default -> throw new IllegalArgumentException(kind);
        }
        assertFalse(StagedDocumentWriter.successfulPublication(task));
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
            () -> new StagedDocumentWriter(transport, "staging", 4096).write("target", encoded, null).block());
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsStagingOnServerlessAndElasticsearch() {
        for (String version : List.of("AOSS 2.0", "ES 7.10")) {
            var transport = mock(RestClient.class);
            var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
                Version.fromString(version), CompressionMode.UNCOMPRESSED, 100, 4096);
            assertThrows(IllegalArgumentException.class, () -> client.setLargeDocumentStagingIndex("staging"));
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

    @Test
    void refusesToUseAnExistingIndexWithoutTheStagingContract() {
        var transport = mock(RestClient.class);
        when(transport.asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any())).thenAnswer(call -> {
            HttpMethod method = call.getArgument(0);
            assertTrue(method.equals(HttpMethod.PUT) || method.equals(HttpMethod.GET),
                "No document writes may reach an unrelated index");
            return Mono.just(method.equals(HttpMethod.PUT)
                ? new HttpResponse(400, "Bad Request", Map.of(),
                    "{\"error\":{\"type\":\"resource_already_exists_exception\"}}")
                : new HttpResponse(200, "OK", Map.of(), "{\"staging\":{\"mappings\":{},\"settings\":{}}}"));
        });
        var operation = BulkNdjson.encode(IndexOp.builder()
            .operation(IndexOperationMeta.builder().index("target").id("id").build())
            .document(Map.of("text", "x".repeat(10_000))).build(), ObjectMapperFactory.createDefaultMapper());
        assertThrows(IllegalArgumentException.class,
            () -> new StagedDocumentWriter(transport, "staging", 4096).write("target", operation, null).block());
        verify(transport, times(2)).asyncRequestBytesWithoutRetry(any(), any(), any(), any(), any());
    }
}
