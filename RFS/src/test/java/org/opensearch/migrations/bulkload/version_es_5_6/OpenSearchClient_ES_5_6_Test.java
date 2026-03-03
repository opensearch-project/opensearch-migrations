package org.opensearch.migrations.bulkload.version_es_5_6;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntry;

@ExtendWith(MockitoExtension.class)
class OpenSearchClient_ES_5_6_Test {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock
    ConnectionContext connectionContext;

    @Mock
    FailedRequestsLogger failedRequestLogger;

    OpenSearchClient openSearchClient;

    @BeforeEach
    void beforeTest() {
        openSearchClient = spy(new OpenSearchClient_ES_5_6(restClient, failedRequestLogger, Version.fromString("ES_5_6"), CompressionMode.UNCOMPRESSED));
    }

    @SneakyThrows
    @Test
    void testCreateIndex() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"created\":\"yup!\"}";
        var createItemResponse = new HttpResponse(200, "", null, createdItemRawJson);

        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var body = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        var result = openSearchClient.createIndex("indexName", body, mock(ICheckedIdempotentPutRequestContext.class));

        // Assertions
        assertThat(result.get().toPrettyString(), containsString(rawJson));
        // The interface is to send back the passed json if on success
        assertThat(result.get().toPrettyString(), not(containsString(createdItemRawJson)));

        Mockito.verify(restClient).putAsync("indexName", "{}", null);
    }

    @Test
    void testBulkRequestHasTypedoc() {
        var docId1 = "tt1979320";
        var docId2 = "tt0816711";

        var successResponse = bulkItemResponse(false, List.of(itemEntry(docId1), itemEntry(docId2)));
        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(successResponse));

        var result = openSearchClient.sendBulkRequest("indexName", bulkDocs, mock(IRfsContexts.IRequestContext.class), false, DocumentExceptionAllowlist.empty()).block();

        Mockito.verify(restClient).postAsync(eq("indexName/doc/_bulk"), any(), any(), any());

        verifyNoInteractions(failedRequestLogger);
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkOperationSpec createBulkDoc(String docId) {
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
