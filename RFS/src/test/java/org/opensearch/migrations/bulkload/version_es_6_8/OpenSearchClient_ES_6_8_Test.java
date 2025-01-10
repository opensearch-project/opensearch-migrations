package org.opensearch.migrations.bulkload.version_es_6_8;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.BulkDocSection;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntry;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntryFailure;

@ExtendWith(MockitoExtension.class)
class OpenSearchClient_ES_6_8_Test {
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
        doReturn(connectionContext).when(restClient).getConnectionContext();
        openSearchClient = spy(new OpenSearchClientFactory(Version.builder().flavor(Flavor.ELASTICSEARCH).major(6).minor(8).build()).get(restClient, failedRequestLogger));
    }

    @SneakyThrows
    @Test
    void testCreateIndexWithIncludeTypeNameFalse() {
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

        Mockito.verify(restClient).putAsync("indexName?include_type_name=false", "{}", null);
    }

    @Test
    void testBulkRequestHasType_doc() {
        // This test gets stuck! I can't figure out why. Somewhere in the `sendBulkRequest`, it's presumably waiting for
        // an async to return, but I can't figure out what. Running in debug hasn't turned anything up.
        var docId1 = "tt1979320";
        var docId2 = "tt0816711";

        var successResponse = bulkItemResponse(false, List.of(itemEntry(docId1), itemEntry(docId2)));
        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));
        when(restClient.postAsync(any(), any(), any())).thenReturn(Mono.just(successResponse));

        var result = openSearchClient.sendBulkRequest("indexName", bulkDocs, mock(IRfsContexts.IRequestContext.class)).block();

        Mockito.verify(restClient).postAsync("indexName/_doc/_bulk", null, null);

        verifyNoInteractions(failedRequestLogger);
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkDocSection createBulkDoc(String docId) {
        var bulkDoc = mock(BulkDocSection.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(bulkDoc.getId()).thenReturn(docId);
        when(bulkDoc.asBulkIndexString()).thenReturn("BULK-INDEX\nBULK_BODY");
        return bulkDoc;
    }

}
