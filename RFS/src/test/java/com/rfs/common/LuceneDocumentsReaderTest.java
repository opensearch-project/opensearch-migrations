package com.rfs.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rfs.common.TestResources.Snapshot;
import com.rfs.models.ShardMetadata;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import lombok.extern.slf4j.Slf4j;
import org.mockito.MockedStatic;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Slf4j
public class LuceneDocumentsReaderTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Path tempDirectory;

    @BeforeEach
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("test-temp-dir");
        log.atDebug().log("Temporary directory created at: " + tempDirectory);
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.walk(tempDirectory)
            .sorted(Comparator.reverseOrder()) // Delete files before directories
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.atError().setMessage("Failed to delete: " + path).setCause(e).log();
                }
            });
        log.atDebug().log("Temporary directory deleted.");
    }

    static Stream<Arguments> provideSnapshots_ES_7_10() {
        return Stream.of(
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_W_SOFT),
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_WO_SOFT)
        );
    }

    @ParameterizedTest
    @MethodSource("provideSnapshots_ES_7_10")
    public void ReadDocuments_ES_7_10_AsExpected(Snapshot snapshot) {
        final var repo = new FileSystemRepo(snapshot.dir);
        SnapshotRepo.Provider snapShotProvider = new SnapshotRepoProvider_ES_7_10(repo);
        DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);

        final ShardMetadata shardMetadata = new ShardMetadataFactory_ES_7_10(snapShotProvider).fromRepo(snapshot.name, "test_updates_deletes", 0);

        SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                    repoAccessor,
                    tempDirectory,
                    shardMetadata,
                    Integer.MAX_VALUE
                );
        Path luceneDir = unpacker.unpack();

        // Use the LuceneDocumentsReader to get the documents
        Flux<Document> documents = new LuceneDocumentsReader(
            luceneDir,
            ElasticsearchConstants_ES_7_10.SOFT_DELETES_POSSIBLE,
            ElasticsearchConstants_ES_7_10.SOFT_DELETES_FIELD
        ).readDocuments()
            .sort(Comparator.comparing(doc -> Uid.decodeId(doc.getBinaryValue("_id").bytes))); // Sort for consistent order given LuceneDocumentsReader may interleave

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String expectedId = "complexdoc";
            String actualId = Uid.decodeId(doc.getBinaryValue("_id").bytes);

            String expectedSource = "{\"title\":\"This is a doc with complex history\",\"content\":\"Updated!\"}";
            String actualSource = doc.getBinaryValue("_source").utf8ToString();
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "unchangeddoc";
            String actualId = Uid.decodeId(doc.getBinaryValue("_id").bytes);

            String expectedSource = "{\"title\":\"This doc will not be changed\\nIt has multiple lines of text\\nIts source doc has extra newlines.\",\"content\":\"bluh bluh\"}";
            String actualSource = doc.getBinaryValue("_source").utf8ToString();
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "updateddoc";
            String actualId = Uid.decodeId(doc.getBinaryValue("_id").bytes);

            String expectedSource = "{\"title\":\"This is doc that will be updated\",\"content\":\"Updated!\"}";
            String actualSource = doc.getBinaryValue("_source").utf8ToString();
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectComplete().verify();
    }

    @Test
    void testParallelReading() throws Exception {
        // Create a mock IndexReader with multiple leaves (segments)
        int numSegments = 10;
        int docsPerSegment = 100;
        DirectoryReader mockReader = mock(DirectoryReader.class);
        var leaves = new ArrayList<LeafReaderContext>();

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger concurrentDocReads = new AtomicInteger(0);
        AtomicInteger concurrentSegmentReads = new AtomicInteger(0);

        for (int i = 0; i < numSegments; i++) {
            LeafReaderContext context = mock(LeafReaderContext.class);
            LeafReader leafReader = mock(LeafReader.class);
            when(context.reader()).thenAnswer(invocation -> {
                concurrentSegmentReads.incrementAndGet();
                return leafReader;
            });
            when(leafReader.maxDoc()).thenReturn(docsPerSegment);
            when(leafReader.getLiveDocs()).thenReturn(null); // Assume all docs are live

            // Wrap the document method to track concurrency
            when(leafReader.document(anyInt())).thenAnswer(invocation -> {
                concurrentDocReads.incrementAndGet();
                startLatch.await(); // Wait for the latch to be released before proceeding to track concurrency
                Document doc = new Document();
                doc.add(new BinaryDocValuesField("_id", new BytesRef("doc" + invocation.getArgument(0))));
                doc.add(new StoredField("_source", new BytesRef("{\"field\":\"value\"}")));
                return doc;
            });
            leaves.add(context);
        }
        when(mockReader.leaves()).thenReturn(leaves);
        when(mockReader.maxDoc()).thenReturn(docsPerSegment * numSegments);

        // Create a custom LuceneDocumentsReader for testing
        try (MockedStatic<DirectoryReader> mockedDirectoryReader = mockStatic(DirectoryReader.class);
             MockedStatic<FSDirectory> mockedFSDirectory = mockStatic(FSDirectory.class)) {

            mockedFSDirectory.when(() -> FSDirectory.open(any(Path.class))).thenReturn(mock(FSDirectory.class));

            LuceneDocumentsReader reader = new LuceneDocumentsReader(Paths.get("dummy"), true, "dummy_field") {
                @Override
                protected DirectoryReader wrapReader(DirectoryReader reader, boolean softDeletesEnabled, String softDeletesField) throws IOException {
                    return mockReader; // Return the mock reader directly
                }
            };

            AtomicInteger observedConcurrentDocReads = new AtomicInteger(0);
            AtomicInteger observedConcurrentSegments = new AtomicInteger(0);

            // Release the latch after a short delay to allow all threads to be ready
            Schedulers.parallel().schedule(() -> {
                observedConcurrentSegments.set(concurrentSegmentReads.get());
                observedConcurrentDocReads.set(concurrentDocReads.get());
                startLatch.countDown();

            }, 500, TimeUnit.MILLISECONDS);

            // Read documents
            List<Document> actualDocuments = reader.readDocuments()
                .collectList()
                .block(Duration.ofSeconds(5));

            // Verify results
            var expectedConcurrentSegments = 5;
            var expectedConcurrentDocReads = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE -  Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE % expectedConcurrentSegments;
            assertNotNull(actualDocuments);
            assertEquals(numSegments * docsPerSegment, actualDocuments.size());
            assertEquals(expectedConcurrentDocReads, observedConcurrentDocReads.get(), "Expected concurrent document reads to equal DEFAULT_BOUNDED_ELASTIC_SIZE");
            assertEquals(expectedConcurrentSegments, observedConcurrentSegments.get(), "Expected concurrent open segments equal to 5");

        }
    }

    protected void assertDocsEqual(String expectedId, String actualId, String expectedSource, String actualSource) {
        try {
            JsonNode expectedNode = objectMapper.readTree(expectedSource);
            JsonNode actualNode = objectMapper.readTree(actualSource);
            assertEquals(expectedId, actualId);
            assertEquals(expectedNode, actualNode);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
