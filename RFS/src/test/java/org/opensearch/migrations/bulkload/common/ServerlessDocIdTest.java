package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerlessDocIdTest {

    @Mock
    private OpenSearchClient mockClient;

    @Mock
    private IDocumentMigrationContexts.IDocumentReindexContext mockContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockContext.createBulkRequest()).thenReturn(mock(IRfsContexts.IRequestContext.class));
    }

    @Test
    void testServerlessDocIdErrorDetection() {
        String serverlessErrorResponse = "{\"took\":1,\"errors\":true,\"items\":[{\"index\":{\"_index\":\"blog_2023\",\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"Document ID is not supported in create/index operation request\"}}}]}";
        
        var response = new OpenSearchClient.BulkResponse(400, "Bad Request", Map.of(), serverlessErrorResponse);
        
        assertTrue(response.hasServerlessDocIdError());
    }

    @Test
    void testNonServerlessErrorNotDetected() {
        String normalErrorResponse = "{\"took\":1,\"errors\":true,\"items\":[{\"index\":{\"_index\":\"blog_2023\",\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\"}}}]}";
        
        var response = new OpenSearchClient.BulkResponse(400, "Bad Request", Map.of(), normalErrorResponse);
        
        assertFalse(response.hasServerlessDocIdError());
    }

    @Test
    void testServerlessErrorWithoutFlagThrowsException() {
        DocumentReindexer reindexer = new DocumentReindexer(mockClient, 10, 1024, 1, null, false);
        
        String serverlessErrorResponse = "{\"took\":1,\"errors\":true,\"items\":[{\"index\":{\"_index\":\"test\",\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"Document ID is not supported in create/index operation request\"}}}]}";
        var response = new OpenSearchClient.BulkResponse(400, "Bad Request", Map.of(), serverlessErrorResponse);
        
        when(mockClient.sendBulkRequest(eq("test-index"), any(), any(), eq(false)))
            .thenReturn(Mono.error(new OpenSearchClient.ServerlessDocIdNotSupportedException(
                "Target cluster does not support custom document IDs.", response)));
        
        Flux<RfsLuceneDocument> documentStream = Flux.just(
            new RfsLuceneDocument(1, "1", null, "{\"field\":\"value\"}", null, RfsDocumentOperation.INDEX)
        );
        
        StepVerifier.create(reindexer.reindex("test-index", documentStream, mockContext))
            .expectError(OpenSearchClient.ServerlessDocIdNotSupportedException.class)
            .verify();
    }

    @Test
    void testServerlessErrorWithFlagStripsIds() {
        DocumentReindexer reindexer = new DocumentReindexer(mockClient, 10, 1024, 1, null, true);
        
        when(mockClient.sendBulkRequest(eq("test-index"), any(), any(), eq(true)))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "OK", Map.of(), "{\"took\":1,\"errors\":false,\"items\":[{}]}")));
        
        Flux<RfsLuceneDocument> documentStream = Flux.just(
            new RfsLuceneDocument(1, "1", null, "{\"field\":\"value\"}", null, RfsDocumentOperation.INDEX)
        );
        
        StepVerifier.create(reindexer.reindex("test-index", documentStream, mockContext))
            .expectNextCount(1)
            .verifyComplete();
        
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IndexOp>> captor = ArgumentCaptor.forClass((Class<List<IndexOp>>)(Class<?>) List.class);
        verify(mockClient).sendBulkRequest(eq("test-index"), captor.capture(), any(), eq(true));
        
        // Verify the request was made with allowServerGeneratedIds=true
        assertTrue(captor.getValue().size() > 0);
    }
}
