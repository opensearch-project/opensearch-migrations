package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.lucene.LuceneDocumentsReader;
import org.opensearch.migrations.bulkload.lucene.LuceneDocumentsReader9;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import shadow.lucene9.org.apache.lucene.document.BinaryDocValuesField;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.document.StoredField;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.LeafReaderContext;
import shadow.lucene9.org.apache.lucene.index.SegmentReader;
import shadow.lucene9.org.apache.lucene.index.StoredFields;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
public class LuceneDocumentsReaderTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Path tempDirectory;

    @BeforeEach
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("test-temp-dir");
        log.atDebug().setMessage("Temporary directory created at: {}").addArgument(tempDirectory).log();
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.walk(tempDirectory)
            .sorted(Comparator.reverseOrder()) // Delete files before directories
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.atError().setCause(e).setMessage("Failed to delete: {}").addArgument(path).log();
                }
            });
        log.atDebug().log("Temporary directory deleted.");
    }

    static Stream<Arguments> provideSnapshots() {
        return Stream.of(
            Arguments.of(TestResources.SNAPSHOT_ES_6_8, Version.fromString("ES 6.8")),
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_W_SOFT, Version.fromString("ES 7.10")),
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_WO_SOFT, Version.fromString("ES 7.10"))
        );
    }

    @ParameterizedTest
    @MethodSource("provideSnapshots")
    public void ReadDocuments_AsExpected(TestResources.Snapshot snapshot, Version version) {
        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);
        DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);

        final ShardMetadata shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshot.name, "test_updates_deletes", 0);

        SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                    repoAccessor,
                    tempDirectory,
                    shardMetadata,
                    Integer.MAX_VALUE
                );
        Path luceneDir = unpacker.unpack();

        // Use the LuceneDocumentsReader to get the documents
        var reader = new LuceneDocumentsReader.Factory(sourceResourceProvider).getReader(luceneDir);

        Flux<RfsLuceneDocument> documents = reader.readDocuments();

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String expectedId = "complexdoc";
            String actualId = doc.id;

            String expectedType = null;
            String actualType = doc.type;

            String expectedSource = "{\"title\":\"This is a doc with complex history\",\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "updateddoc";
            String actualId = doc.id;

            String expectedType = null;
            String actualType = doc.type;

            String expectedSource = "{\"title\":\"This is doc that will be updated\",\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType,
                    expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "unchangeddoc";
            String actualId = doc.id;

            String expectedType = null;
            String actualType = doc.type;

            String expectedSource = "{\"title\":\"This doc will not be changed\\nIt has multiple lines of text\\nIts source doc has extra newlines.\",\"content\":\"bluh bluh\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType,
                    expectedSource, actualSource);
            return true;
        }).expectComplete().verify();
    }

    @Test
    public void ReadDocuments_ES5_Origin_AsExpected() {
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_6_8_MERGED;
        Version version = Version.fromString("ES 6.8");

        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);
        DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);

        final ShardMetadata shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshot.name, "test_updates_deletes", 0);

        SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                    repoAccessor,
                    tempDirectory,
                    shardMetadata,
                    Integer.MAX_VALUE
                );
        Path luceneDir = unpacker.unpack();

        // Use the LuceneDocumentsReader to get the documents
        var reader = new LuceneDocumentsReader.Factory(sourceResourceProvider).getReader(luceneDir);

        Flux<RfsLuceneDocument> documents = reader.readDocuments();

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String expectedId = "unchangeddoc";
            String actualId = doc.id;

            String expectedType = "type2";
            String actualType = doc.type;

            String expectedSource = "{\"content\":\"This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "updateddoc";
            String actualId = doc.id;

            String expectedType = "type2";
            String actualType = doc.type;

            String expectedSource = "{\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType,
                    expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "complexdoc";
            String actualId = doc.id;

             String expectedType = "type1";
             String actualType = doc.type;

            String expectedSource = "{\"title\":\"This is a doc with complex history. Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedType, actualType,
                    expectedSource, actualSource);
            return true;
        }).expectComplete().verify();
    }

    @Test
    @Tag("isolatedTest")
    void testParallelReading() throws Exception {
        // Create a mock IndexReader with multiple leaves (segments)
        int numSegments = 10;
        int docsPerSegment = 100;
        DirectoryReader mockReader = mock(DirectoryReader.class);
        var leaves = new ArrayList<LeafReaderContext>();

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger concurrentDocReads = new AtomicInteger(0);
        var segmentReadTracker = new ConcurrentHashMap<String, AtomicBoolean>();
        AtomicInteger concurrentSegmentReads = new AtomicInteger(0);

        for (int i = 0; i < numSegments; i++) {
            LeafReaderContext context = mock(LeafReaderContext.class);
            SegmentReader leafReader = mock(SegmentReader.class);
            when(context.reader()).thenAnswer(invocation -> leafReader);
            var segmentName = "__" + i;
            when(leafReader.getSegmentName()).thenReturn(segmentName);
            segmentReadTracker.put(segmentName, new AtomicBoolean(false));
            when(leafReader.maxDoc()).thenReturn(docsPerSegment);
            when(leafReader.getLiveDocs()).thenReturn(null); // Assume all docs are live

            var storedFields = mock(StoredFields.class);
            when(leafReader.storedFields()).thenReturn(storedFields);
            // Wrap the document method to track concurrency
            when(storedFields.document(anyInt())).thenAnswer(invocation -> {
                if (segmentReadTracker.get(segmentName).compareAndSet(false, true)) {
                    concurrentSegmentReads.incrementAndGet(); // Increment only on first read per segment
                }
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
        LuceneDocumentsReader reader = new LuceneDocumentsReader9(Paths.get("dummy"), false, "dummy_field") {
            @Override
            protected DirectoryReader getReader() {
                return mockReader;
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
        List<RfsLuceneDocument> actualDocuments = reader.readDocuments()
            .subscribeOn(Schedulers.parallel())
            .collectList()
            .block(Duration.ofSeconds(2));

        // Verify results
        var expectedConcurrentSegments = 1; // Segment concurrency disabled for preserved ordering
        var expectedConcurrentDocReads = 100;
        assertNotNull(actualDocuments);
        assertEquals(numSegments * docsPerSegment, actualDocuments.size());
        assertEquals(expectedConcurrentSegments, observedConcurrentSegments.get(), "Expected concurrent open segments equal to " + expectedConcurrentSegments);
        assertEquals(expectedConcurrentDocReads, observedConcurrentDocReads.get(), "Expected concurrent document reads to equal DEFAULT_BOUNDED_ELASTIC_SIZE");
    }

    @Test
    public void ReadDocumentsStartingFromCheckpointForOneSegments_AsExpected() {
        // This snapshot has 6 documents in 1 segment. There are updates and deletes involved, so
        // there are only 3 final documents, which affects which document id the reader should
        // start at.
        var snapshot = TestResources.SNAPSHOT_ES_7_10_W_SOFT;
        var version = Version.fromString("ES 7.10");
        List<List<String>> documentIds = List.of(
                List.of("complexdoc", "updateddoc", "unchangeddoc"),
                List.of("updateddoc", "unchangeddoc"),
                List.of("unchangeddoc"));
        List<Integer> documentStartingIndices = List.of(0, 2, 5);

        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);
        DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);

        final ShardMetadata shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshot.name, "test_updates_deletes", 0);

        SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                repoAccessor,
                tempDirectory,
                shardMetadata,
                Integer.MAX_VALUE
        );
        Path luceneDir = unpacker.unpack();

        // Use the LuceneDocumentsReader to get the documents
        var reader = new LuceneDocumentsReader.Factory(sourceResourceProvider).getReader(luceneDir);


        for (int i = 0; i < documentStartingIndices.size(); i++) {
            Flux<RfsLuceneDocument> documents = reader.readDocuments(documentStartingIndices.get(i));

            var actualDocIds = documents.collectList().block().stream().map(doc -> doc.id).collect(Collectors.joining(","));
            var expectedDocIds = String.join(",", documentIds.get(i));
            Assertions.assertEquals(expectedDocIds, actualDocIds);
        }
    }

    @Test
    public void ReadDocumentsStartingFromCheckpointForManySegments_AsExpected() throws Exception {
        // This snapshot has three segments, each with only a single document.
        var snapshot = TestResources.SNAPSHOT_ES_6_8;
        var version = Version.fromString("ES 6.8");
        List<List<String>> documentIds = List.of(
                List.of("complexdoc", "updateddoc", "unchangeddoc"),
                List.of("updateddoc", "unchangeddoc"),
                List.of("unchangeddoc"));

        final var repo = new FileSystemRepo(snapshot.dir);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo);
        DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);

        final ShardMetadata shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshot.name, "test_updates_deletes", 0);

        SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                repoAccessor,
                tempDirectory,
                shardMetadata,
                Integer.MAX_VALUE
        );
        Path luceneDir = unpacker.unpack();

        // Use the LuceneDocumentsReader to get the documents
        var reader = new LuceneDocumentsReader.Factory(sourceResourceProvider).getReader(luceneDir);


        for (int startingDocIndex = 0; startingDocIndex < documentIds.size(); startingDocIndex++) {
            Flux<RfsLuceneDocument> documents = reader.readDocuments(startingDocIndex);

            var actualDocIds = documents.collectList().block().stream().map(doc -> doc.id).collect(Collectors.joining(","));
            var expectedDocIds = String.join(",", documentIds.get(startingDocIndex));
            Assertions.assertEquals(expectedDocIds, actualDocIds);
        }
    }

    protected void assertDocsEqual(String expectedId, String actualId, String expectedType,
                                   String actualType, String expectedSource, String actualSource) {
        try {
            String sanitizedExpected = expectedSource.trim().replace("\n", "").replace("\\n", "");
            String sanitizedActual = actualSource.trim().replace("\n", "").replace("\\n", "");


            JsonNode expectedNode = objectMapper.readTree(sanitizedExpected);
            JsonNode actualNode = objectMapper.readTree(sanitizedActual);
            assertEquals(expectedId, actualId);
            assertEquals(expectedType, actualType);
            assertEquals(expectedNode, actualNode);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
