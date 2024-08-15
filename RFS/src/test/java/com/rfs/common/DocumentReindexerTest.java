package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import com.rfs.tracing.IRfsContexts;
import lombok.extern.slf4j.Slf4j;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

        when(mockClient.sendBulkRequest(eq("test-index"), any(), any()))
            .thenAnswer(invocation -> {
                List<?> bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.size();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        int expectedBulkRequests = (10 + MAX_DOCS_PER_BULK - 1) / MAX_DOCS_PER_BULK;
        verify(mockClient, times(expectedBulkRequests)).sendBulkRequest(eq("test-index"), any(), any());

        @SuppressWarnings("unchecked")
        var bulkRequestCaptor = ArgumentCaptor.forClass((Class<List<DocumentReindexer.BulkDocSection>>)(Class<?>) List.class);
        verify(mockClient, times(expectedBulkRequests)).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        var capturedBulkRequests = bulkRequestCaptor.getAllValues();
        assertEquals(expectedBulkRequests, capturedBulkRequests.size());

        for (int i = 0; i < expectedBulkRequests - 1; i++) {
            assertEquals(MAX_DOCS_PER_BULK, capturedBulkRequests.get(i).size());
        }

        var lastRequest = capturedBulkRequests.get(expectedBulkRequests - 1);
        int remainingDocs = 10 % MAX_DOCS_PER_BULK;
        assertEquals(remainingDocs, lastRequest.size());
    }

    @Test
    void reindex_shouldBufferBySize() {
        int numDocs = 5;
        Flux<Document> documentStream = Flux.range(1, numDocs)
            .map(i -> createLargeTestDocument(String.valueOf(i), MAX_BULK_SIZE / 2 + 1));

        when(mockClient.sendBulkRequest(eq("test-index"), any(), any()))
            .thenAnswer(invocation -> {
                List<?> bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.size();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(numDocs)).sendBulkRequest(eq("test-index"), any(), any());

        @SuppressWarnings("unchecked")
        var bulkRequestCaptor = ArgumentCaptor.forClass((Class<List<DocumentReindexer.BulkDocSection>>)(Class<?>) List.class);
        verify(mockClient, times(numDocs)).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        var capturedBulkRequests = bulkRequestCaptor.getAllValues();
        assertEquals(numDocs, capturedBulkRequests.size());

        for (var bulkDocSections : capturedBulkRequests) {
            assertEquals(1, bulkDocSections.size());
        }
    }

    @Test
    void reindex_shouldSendDocumentsLargerThanMaxBulkSize() {
        Flux<Document> documentStream = Flux.just(createLargeTestDocument("1", MAX_BULK_SIZE * 3 / 2));

        when(mockClient.sendBulkRequest(eq("test-index"), any(), any()))
            .thenAnswer(invocation -> {
                List<?> bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.size();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(1)).sendBulkRequest(eq("test-index"), any(), any());

        @SuppressWarnings("unchecked")
        var bulkRequestCaptor = ArgumentCaptor.forClass((Class<List<DocumentReindexer.BulkDocSection>>)(Class<?>) List.class);
        verify(mockClient).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        var capturedBulkRequests = bulkRequestCaptor.getValue();
        assertTrue(capturedBulkRequests.get(0).asBulkIndex().getBytes(StandardCharsets.UTF_8).length > MAX_BULK_SIZE, "Bulk request should be larger than max bulk size");
        assertEquals(1, capturedBulkRequests.size(), "Should contain 1 document");
    }

    @Test
    void reindex_shouldTrimAndRemoveNewlineFromSource() {
        Flux<Document> documentStream = Flux.just(createTestDocumenWithWhitespace("1"));

        when(mockClient.sendBulkRequest(eq("test-index"), any(), any()))
            .thenAnswer(invocation -> {
                List<?> bulkBody = invocation.getArgument(1);
                long docCount = bulkBody.size();
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null,
                    String.format("{\"took\":1,\"errors\":false,\"items\":[%s]}", "{}".repeat((int)docCount))));
            });

        StepVerifier.create(documentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(1)).sendBulkRequest(eq("test-index"), any(), any());

        @SuppressWarnings("unchecked")
        var bulkRequestCaptor = ArgumentCaptor.forClass((Class<List<DocumentReindexer.BulkDocSection>>)(Class<?>) List.class);
        verify(mockClient).sendBulkRequest(eq("test-index"), bulkRequestCaptor.capture(), any());

        var capturedBulkRequests = bulkRequestCaptor.getValue();
        assertEquals(1, capturedBulkRequests.size(), "Should contain 1 document");
        assertEquals("{\"index\":{\"_id\":\"MQAA\"}}\n{\"field\":\"value\"}", capturedBulkRequests.get(0).asBulkIndex());
    }

    private Document createTestDocument(String id) {
        Document doc = new Document();
        doc.add(new StringField("_id", new BytesRef(id), Field.Store.YES));
        doc.add(new StringField("_source", new BytesRef("{\"field\":\"value\"}"), Field.Store.YES));
        return doc;
    }

    private Document createTestDocumenWithWhitespace(String id) {
        Document doc = new Document();
        doc.add(new StringField("_id", new BytesRef(id), Field.Store.YES));
        doc.add(new StringField("_source", new BytesRef(" \r\n\t{\"field\"\n:\"value\"}\r\n\t "), Field.Store.YES));
        return doc;
    }

    private Document createLargeTestDocument(String id, int size) {
        Document doc = new Document();
        doc.add(new StringField("_id", new BytesRef(id), Field.Store.YES));
        String largeField = "x".repeat(size);
        doc.add(new StringField("_source", new BytesRef("{\"field\":\"" + largeField + "\"}"), Field.Store.YES));
        return doc;
    }

    @Test
    void reindex_shouldRespectMaxConcurrentRequests() {
        int numDocs = 100;
        int maxConcurrentRequests = 5;
        DocumentReindexer concurrentReindexer = new DocumentReindexer(mockClient, 1, MAX_BULK_SIZE, maxConcurrentRequests);

        Flux<Document> documentStream = Flux.range(1, numDocs).map(i -> createTestDocument(String.valueOf(i)));

        AtomicInteger concurrentRequests = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        when(mockClient.sendBulkRequest(eq("test-index"), any(), any()))
            .thenAnswer(invocation -> {
                int current = concurrentRequests.incrementAndGet();
                maxObservedConcurrency.updateAndGet(max -> Math.max(max, current));
                return Mono.just(new OpenSearchClient.BulkResponse(200, "OK", null, "{\"took\":1,\"errors\":false,\"items\":[{}]}"))
                    .delayElement(Duration.ofMillis(100))
                    .doOnTerminate(concurrentRequests::decrementAndGet);
            });

        StepVerifier.create(concurrentReindexer.reindex("test-index", documentStream, mockContext))
            .verifyComplete();

        verify(mockClient, times(numDocs)).sendBulkRequest(eq("test-index"), any(), any());
        assertTrue(maxObservedConcurrency.get() <= maxConcurrentRequests,
            "Max observed concurrency (" + maxObservedConcurrency.get() +
            ") should not exceed max concurrent requests (" + maxConcurrentRequests + ")");
    }
}
