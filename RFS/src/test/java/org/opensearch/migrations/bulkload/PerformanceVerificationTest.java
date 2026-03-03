import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.DocumentReaderEngine.DocumentChangeset;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClient.BulkResponse;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.version_9.DirectoryReader9;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.document.StoredField;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.IndexWriter;
import shadow.lucene9.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene9.org.apache.lucene.store.ByteBuffersDirectory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
@Disabled("https://opensearch.atlassian.net/browse/MIGRATIONS-2254")
public class PerformanceVerificationTest {

    @Test
    @Tag("isolatedTest")
    void testDocumentBuffering() throws Exception {
        // Create an in-memory directory for the test
        ByteBuffersDirectory inMemoryDir = new ByteBuffersDirectory();

        for (int segment = 0; segment < 5; segment++) {
            // Create and populate the in-memory index
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(inMemoryDir, config)) {
                for (int i = 0; i < 100_000; i++) {
                    Document doc = new Document();
                    String id = "doc" + i;
                    doc.add(new StoredField("_id", new BytesRef(id)));
                    doc.add(new StoredField("_source", new BytesRef("{\"field\":\"value\"}")));
                    writer.addDocument(doc);
                }
                writer.commit();
            }
        }

        // Create a real DirectoryReader using the in-memory index
        DirectoryReader realReader = DirectoryReader.open(inMemoryDir);
        var segmentsFileName = realReader.getIndexCommit().getSegmentsFileName();

        // Create a custom LuceneDocumentsReader for testing
        AtomicInteger ingestedDocuments = new AtomicInteger(0);
        var reader = new IndexReader9(Paths.get("dummy"), true, "dummy_field") {
            @Override
            public LuceneDirectoryReader getReader(String ignored) {
                return new DirectoryReader9(realReader, Path.of("in", "memory"));
            }
        };

        // Create a mock OpenSearchClient with a pause
        AtomicInteger sentDocuments = new AtomicInteger(0);
        CountDownLatch pauseLatch = new CountDownLatch(1);
        OpenSearchClient mockClient = mock(OpenSearchClient.class);
        when(mockClient.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            List<BulkOperationSpec> docs = invocation.getArgument(1);
            sentDocuments.addAndGet(docs.size());
            var response = new BulkResponse(200, "OK", null, null);
            var blockingScheduler = Schedulers.newSingle("TestWaiting");
            return Mono.fromCallable(() -> {
                    // Perform wait on separate thread to simulate nio behavior
                    pauseLatch.await();
                    return null;
                }).subscribeOn(blockingScheduler)
                .then(Mono.just(response))
                .doFinally(s -> blockingScheduler.dispose());
        });

        // Create DocumentReindexer
        int maxDocsPerBulkRequest = 1000;
        long maxBytesPerBulkRequest = Long.MAX_VALUE; // No Limit on Size
        int maxConcurrentWorkItems = 10;
        DocumentReindexer reindexer = new DocumentReindexer(mockClient, maxDocsPerBulkRequest, maxBytesPerBulkRequest, maxConcurrentWorkItems, () -> null, false);

        // Create a mock IDocumentReindexContext
        IDocumentMigrationContexts.IDocumentReindexContext mockContext = mock(IDocumentMigrationContexts.IDocumentReindexContext.class);
        when(mockContext.createBulkRequest()).thenReturn(mock(IRfsContexts.IRequestContext.class));

        Flux<LuceneDocumentChange> documentsStream = reader.streamDocumentChanges(segmentsFileName).map(d -> {
            ingestedDocuments.incrementAndGet();
            return d;
        });

        // Start reindexing in a separate thread
        Thread reindexThread = new Thread(() -> {
            reindexer.reindex("test-index", new DocumentChangeset(Flux.empty(), documentsStream, () -> {}), mockContext).then().block();
        });
        reindexThread.start();

        // Wait until ingested and sent document counts stabilize
        int previousIngestedDocs = 0;
        int previousSentDocs = 0;
        int ingestedDocs = 0;
        int sentDocs = 0;
        boolean stabilized = false;

        long startTime = System.currentTimeMillis();
        while (!stabilized) {
            if (System.currentTimeMillis() - startTime > 30000) {
                throw new AssertionError("Test timed out after 30 seconds");
            }
            Thread.sleep(1000);
            ingestedDocs = ingestedDocuments.get();
            sentDocs = sentDocuments.get();

            if (ingestedDocs == previousIngestedDocs && sentDocs == previousSentDocs) {
                stabilized = true;
            } else {
                previousIngestedDocs = ingestedDocs;
                previousSentDocs = sentDocs;
            }
        }

        // Release the pause and wait for the reindex to complete
        pauseLatch.countDown();
        reindexThread.join(30000); // fail if not complete in 30 seconds

        // Assert that we had buffered expected number of documents
        int bufferedDocs = ingestedDocs - sentDocs;

        log.info("In Flight Docs: {}, Buffered Docs: {}", sentDocs, bufferedDocs);
        int expectedSentDocs = maxDocsPerBulkRequest * maxConcurrentWorkItems;
        assertEquals(expectedSentDocs, sentDocs, "Expected sent docs to equal maxDocsPerBulkRequest * maxConcurrentWorkItems");

        int expectedConcurrentDocReads = 100;
        int expectedBulkDocsBuffered = 50;
        int docsFromBuffers = expectedBulkDocsBuffered * maxDocsPerBulkRequest;
        int numberOfSingleBufferSteps = 2; // calls like publishOn(scheduler, 1) holds a 1 item buffer
        int strictExpectedBufferedDocs = docsFromBuffers + expectedConcurrentDocReads + numberOfSingleBufferSteps;
        // Assert that the number of buffered documents is within 1 of the expected number
        assertEquals(strictExpectedBufferedDocs, bufferedDocs, 1);

        // Verify the total number of ingested documents
        assertEquals(500_000, ingestedDocuments.get(), "Not all documents were ingested");
        assertEquals(500_000, sentDocuments.get(), "Not all documents were sent");

    }
}
