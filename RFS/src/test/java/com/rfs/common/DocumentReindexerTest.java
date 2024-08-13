package com.rfs.common;

import com.rfs.tracing.IRfsContexts;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class DocumentReindexerTest {

    private static final int MAX_DOCS_PER_BULK = 3;
    private static final int MAX_BULK_SIZE = 1000;
    private static final int MAX_CONCURRENT_REQUESTS = 1;

    @Mock
    private OpenSearchClient mockClient;

    @Mock
    private IDocumentMigrationContexts.IDocumentReindexContext mockContext;

    private DocumentReindexer documentReindexer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        documentReindexer = new DocumentReindexer(mockClient, MAX_DOCS_PER_BULK, MAX_BULK_SIZE, MAX_CONCURRENT_REQUESTS);
        when(mockContext.createBulkRequest()).thenReturn(mock(IRfsContexts.IRequestContext.class));
    }

    @Test
    void reindex_shouldBufferByDocumentCount() {
        Flux<Document> documentStream = Flux.range(1, 10)
            .map(i -> createTestDocument(String.valueOf(i)));

        when(mockClient.sendBulkRequest(eq("test-index"), anyString(), any()))
            .thenAnswer(invocation -> {
                String bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.lines().filter(line -> line.contains("\"index\":")).count();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        int expectedBulkRequests = (10 + MAX_DOCS_PER_BULK - 1) / MAX_DOCS_PER_BULK;
        verify(mockClient, times(expectedBulkRequests)).sendBulkRequest(eq("test-index"), anyString(), any());

        ArgumentCaptor<String> bulkRequestCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient, times(expectedBulkRequests)).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        List<String> capturedBulkRequests = bulkRequestCaptor.getAllValues();
        assertEquals(expectedBulkRequests, capturedBulkRequests.size());

        for (int i = 0; i < expectedBulkRequests - 1; i++) {
            String request = capturedBulkRequests.get(i);
            assertEquals(MAX_DOCS_PER_BULK * 2, request.split("\n").length); // MAX_DOCS_PER_BULK index actions + MAX_DOCS_PER_BULK documents
        }

        String lastRequest = capturedBulkRequests.get(expectedBulkRequests - 1);
        int remainingDocs = 10 % MAX_DOCS_PER_BULK;
        assertEquals(remainingDocs * 2, lastRequest.split("\n").length); // remainingDocs index actions + remainingDocs documents
    }

    @Test
    void reindex_shouldBufferBySize() {
        int numDocs = 5;
        Flux<Document> documentStream = Flux.range(1, numDocs)
            .map(i -> createLargeTestDocument(String.valueOf(i), MAX_BULK_SIZE / 2 + 1));

        when(mockClient.sendBulkRequest(eq("test-index"), anyString(), any()))
            .thenAnswer(invocation -> {
                String bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.lines().filter(line -> line.contains("\"index\":")).count();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(numDocs)).sendBulkRequest(eq("test-index"), anyString(), any());

        ArgumentCaptor<String> bulkRequestCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient, times(numDocs)).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        List<String> capturedBulkRequests = bulkRequestCaptor.getAllValues();
        assertEquals(numDocs, capturedBulkRequests.size());

        for (String request : capturedBulkRequests) {
            assertEquals(2, request.split("\n").length); // 1 index action + 1 document
        }
    }

    @Test
    void reindex_shouldSendDocumentsLargerThanMaxBulkSize() {
        Flux<Document> documentStream = Flux.just(createLargeTestDocument("1", MAX_BULK_SIZE * 3 / 2));

        when(mockClient.sendBulkRequest(eq("test-index"), anyString(), any()))
            .thenAnswer(invocation -> {
                String bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.lines().filter(line -> line.contains("\"index\":")).count();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(1)).sendBulkRequest(eq("test-index"), anyString(), any());

        ArgumentCaptor<String> bulkRequestCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        String capturedBulkRequest = bulkRequestCaptor.getValue();
        assertTrue(capturedBulkRequest.length() > MAX_BULK_SIZE, "Bulk request should be larger than max bulk size");
        assertEquals(2, capturedBulkRequest.split("\n").length, "Should contain 1 index action and 1 document");
    }

    private Document createTestDocument(String id) {
        Document doc = new Document();
        doc.add(new StringField("_id", new BytesRef(id), Field.Store.YES));
        doc.add(new StringField("_source", new BytesRef("{\"field\":\"value\"}"), Field.Store.YES));
        return doc;
    }

    private Document createLargeTestDocument(String id, int size) {
        Document doc = new Document();
        doc.add(new StringField("_id", new BytesRef(id), Field.Store.YES));
        String largeField = "x".repeat(size);
        doc.add(new StringField("_source", new BytesRef("{\"field\":\"" + largeField + "\"}"), Field.Store.YES));
        return doc;
    }
}
