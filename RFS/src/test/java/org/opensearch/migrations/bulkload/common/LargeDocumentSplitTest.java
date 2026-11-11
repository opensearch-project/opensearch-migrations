package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real client and serializer against an in-memory target that applies bulk create, index and
 * partial-update actions the way OpenSearch does. The same behavior is checked against real clusters in
 * {@code LargeDocumentSplitEndToEndTest}.
 */
class LargeDocumentSplitTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 512;
    private static final String INDEX = "idx";
    private static final String PLAIN_INDEX = "{\"idx\":{\"mappings\":{},\"settings\":{\"index.number_of_shards\":\"1\"}}}";
    // Nearly twice the request size, including a nested object that itself exceeds one request.
    private static final String LARGE = "{\"title\":\"" + "t".repeat(150) + "\",\"ratio\":0.5,"
        + "\"profile\":{\"a\":\"" + "a".repeat(200) + "\",\"b\":\"" + "b".repeat(200) + "\",\"c\":[1,2,3]},"
        + "\"body\":\"" + "x".repeat(250) + "\",\"tail\":true}";

    private final RestClient transport = mock(RestClient.class);
    private final FailedDocumentStreamSink failures = mock(FailedDocumentStreamSink.class);
    private final List<String> requests = new ArrayList<>();
    private final Map<String, ObjectNode> stored = new HashMap<>();
    private final Map<String, Long> seqNos = new HashMap<>();
    private long nextSeqNo;
    private String indexDefinition = PLAIN_INDEX;
    /** Lets a test answer a request itself (by its position and action type); null falls through to the target. */
    private BiFunction<Integer, String, ObjectNode> intercept = (position, type) -> null;

    @BeforeEach
    void stubTransport() {
        when(transport.getConnectionContext()).thenReturn(mock(ConnectionContext.class));
        when(transport.withDefaultHeaders(any())).thenReturn(transport);
        when(transport.getAsync(anyString(), any()))
            .thenAnswer(invocation -> Mono.just(new HttpResponse(200, "OK", Map.of(), indexDefinition)));
        when(transport.postAsyncBytes(any(), any(), any(), any()))
            .thenAnswer(invocation -> Mono.just(bulk(invocation.getArgument(1))));
        when(failures.write(any())).thenReturn(Mono.empty());
    }

    private OpenSearchClient client(String version, boolean split) {
        var client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
            Version.fromString(version), CompressionMode.UNCOMPRESSED, 100, MAX_REQUEST_BYTES) {
            @Override
            protected Retry getBulkRetryStrategy() {
                return Retry.max(0);
            }
        };
        client.setFailedDocumentStreamContext(failures, "session", "worker");
        client.setSplitLargeDocuments(split);
        return client;
    }

    private OpenSearchClient.BulkResponse send(OpenSearchClient client, boolean serverGeneratedIds, String... sources) {
        var documents = new ArrayList<Document>();
        for (int i = 0; i < sources.length; i++) {
            documents.add(new Document("doc" + i, sources[i].getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(Document.HINT_ROUTING, "r"), Map.of()));
        }
        return client.sendBulkRequestRaw(INDEX, documents, mock(IRfsContexts.IRequestContext.class),
            serverGeneratedIds, DocumentExceptionAllowlist.empty()).block();
    }

    /** Applies every action of one bulk request to the in-memory target. */
    private HttpResponse bulk(byte[] request) throws Exception {
        requests.add(new String(request, StandardCharsets.UTF_8));
        var lines = new String(request, StandardCharsets.UTF_8).split("\n");
        var response = MAPPER.createObjectNode();
        var items = response.putArray("items");
        for (int line = 0; line < lines.length; line += 2) {
            var action = MAPPER.readTree(lines[line]);
            var type = action.fieldNames().next();
            var metadata = action.get(type);
            var item = intercept.apply(requests.size(), type);
            if (item == null) {
                item = apply(type, metadata, (ObjectNode) MAPPER.readTree(lines[line + 1]));
            }
            response.put("errors", response.path("errors").asBoolean() || item.has("error"));
            items.addObject().set(type, item.put("_index", INDEX).put("_id", metadata.path("_id").asText(null)));
        }
        return new HttpResponse(200, "OK", Map.of(), response.toString());
    }

    private ObjectNode apply(String type, JsonNode metadata, ObjectNode body) {
        var id = metadata.path("_id").asText("generated" + nextSeqNo);
        var existing = stored.get(id);
        if ("create".equals(type) && existing != null
            || metadata.has("if_seq_no") && metadata.get("if_seq_no").asLong() != seqNos.get(id)) {
            return error(409, "version_conflict_engine_exception");
        }
        if ("update".equals(type)) {
            if (existing == null) {
                return error(404, "document_missing_exception");
            }
            if (!body.get("doc").isEmpty()) {
                JsonObjectSplitterTest.merge(existing, (ObjectNode) body.get("doc"));
                seqNos.put(id, nextSeqNo++);
            }
        } else {
            stored.put(id, body);
            seqNos.put(id, nextSeqNo++);
        }
        return MAPPER.createObjectNode().put("status", existing == null ? 201 : 200)
            .put("_seq_no", seqNos.get(id)).put("_primary_term", 1);
    }

    private static ObjectNode error(int status, String type) {
        var item = MAPPER.createObjectNode().put("status", status);
        item.putObject("error").put("type", type).put("reason", "test");
        return item;
    }

    private List<String> actionTypes() {
        return requests.stream().map(request -> request.substring(2, request.indexOf('"', 2))).toList();
    }

    @Test
    void documentsThatFitAreSentExactlyAsWithoutTheOption() {
        send(client("OS 2.11", false), false, "{\"a\":1}", "{\"b\":2}");
        send(client("OS 2.11", true), false, "{\"a\":1}", "{\"b\":2}");

        assertEquals(2, requests.size());
        assertEquals(requests.get(0), requests.get(1));
        verify(transport, never()).getAsync(anyString(), any());
    }

    @Test
    void oversizedDocumentIsCreatedThenMergedInBoundedRequests() throws Exception {
        var response = send(client("OS 2.11", true).forCollection("collection"), false, "{\"a\":1}", LARGE, "{\"b\":2}");

        assertEquals(MAPPER.readTree(LARGE).toString(), stored.get("doc1").toString());
        assertEquals(Set.of("doc0", "doc1", "doc2"), stored.keySet());
        var types = actionTypes();
        assertEquals("create", types.get(1));
        assertTrue(types.size() > 4 && types.subList(2, types.size() - 1).stream().allMatch("update"::equals), types.toString());
        for (var request : requests) {
            assertTrue(request.getBytes(StandardCharsets.UTF_8).length <= MAX_REQUEST_BYTES, request);
            assertTrue(request.contains("\"routing\":\"r\""), request);
        }
        // The document counts once, by its own size, however many requests carried it.
        assertEquals(new MigrationMetrics.Operations(3, 0, 14 + LARGE.length()), response.target);
        assertEquals(requests.size(), response.requests.total());
        verify(failures, never()).write(any());
    }

    @Test
    void existingDocumentIsReplacedAtTheSequenceNumberJustRead() throws Exception {
        var client = client("OS 2.11", true);
        send(client, false, LARGE.replace("\"tail\":true", "\"stale\":{\"gone\":1}"));
        requests.clear();
        // Another writer moves the document between the read and the first conditional replacement.
        var moved = new long[1];
        intercept = (position, type) -> {
            if (position == 3) {
                moved[0] = nextSeqNo++;
                seqNos.put("doc0", moved[0]);
            }
            return null;
        };

        send(client, false, LARGE);

        assertEquals(MAPPER.readTree(LARGE).toString(), stored.get("doc0").toString());
        assertEquals(List.of("create", "update", "index", "update", "index"), actionTypes().subList(0, 5));
        assertTrue(requests.get(1).endsWith("\n{\"doc\":{}}\n"), requests.get(1));
        assertFalse(requests.get(2).contains("\"if_seq_no\":" + moved[0] + ","), requests.get(2));
        assertTrue(requests.get(4).contains("\"if_seq_no\":" + moved[0] + ","), requests.get(4));
        assertFalse(stored.get("doc0").has("stale"));
        verify(failures, never()).write(any());
    }

    @Test
    void serverlessReplacesAnExistingDocumentWithoutACondition() throws Exception {
        var client = client("AOSS 2.11", true);
        send(client, false, "{\"stale\":1}");
        requests.clear();

        send(client, false, LARGE);

        assertEquals(MAPPER.readTree(LARGE).toString(), stored.get("doc0").toString());
        assertEquals(List.of("create", "index", "update"), actionTypes().subList(0, 3));
        assertFalse(requests.get(1).contains("if_seq_no"), requests.get(1));
    }

    @Test
    void rejectedFragmentStopsTheSequenceAndRecordsTheWholeDocument() {
        intercept = (position, type) -> position == 3 ? error(400, "mapper_parsing_exception") : null;

        send(client("OS 2.11", true), false, LARGE);

        assertEquals(List.of("create", "update", "update"), actionTypes());
        var record = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
        verify(failures).write(record.capture());
        assertEquals("doc0", record.getValue().getDocumentId());
        assertEquals("mapper_parsing_exception", record.getValue().getFailureType());
        assertTrue(record.getValue().getRequestItem().toString().contains("x".repeat(250)), "the whole document");
        assertTrue(record.getValue().getResponseItem().has("update"), "the request that failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"idx\":{\"mappings\":{\"_source\":{\"excludes\":[\"body\"]}},\"settings\":{}}}",
        "{\"idx\":{\"mappings\":{\"_source\":{\"enabled\":false}},\"settings\":{}}}",
        "{\"idx\":{\"mappings\":{},\"settings\":{\"index.default_pipeline\":\"p\"}}}",
        "{\"idx\":{\"mappings\":{},\"settings\":{\"index.final_pipeline\":\"p\"}}}",
        "{\"idx\":{\"mappings\":{},\"settings\":{\"index.derived_source.enabled\":\"true\"}}}",
        "{}"
    })
    void oversizedDocumentIsSentWholeWhenItsIndexCannotTakeMerges(String definition) {
        indexDefinition = definition;
        assertSentWholeAsWithoutTheOption(false, LARGE);
        verify(transport).getAsync(eq(INDEX + "?flat_settings=true"), any());
    }

    @Test
    void oversizedDocumentIsSentWholeWhenItCannotBeSplit() {
        assertSentWholeAsWithoutTheOption(false, "{\"body\":\"" + "x".repeat(1000) + "\"}");
        assertSentWholeAsWithoutTheOption(false, LARGE.replace("\"tail\"", "\"title\""));
        assertSentWholeAsWithoutTheOption(true, LARGE);
        verify(transport, never()).getAsync(anyString(), any());
    }

    private void assertSentWholeAsWithoutTheOption(boolean serverGeneratedIds, String source) {
        requests.clear();
        send(client("OS 2.11", false), serverGeneratedIds, source);
        send(client("OS 2.11", true), serverGeneratedIds, source);
        assertEquals(2, requests.size());
        assertArrayEquals(requests.get(0).getBytes(StandardCharsets.UTF_8), requests.get(1).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void optionRequiresAnOpenSearchTarget() {
        assertThrows(IllegalArgumentException.class, () -> client("ES 7.10", true));
    }
}
