package org.opensearch.migrations.bulkload.common;

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

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

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

    static Stream<Arguments> provideSnapshots() {
        return Stream.of(
            Arguments.of(TestResources.SNAPSHOT_ES_6_8, Version.fromString("ES 6.8")),
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_W_SOFT, Version.fromString("ES 7.10")),
            Arguments.of(TestResources.SNAPSHOT_ES_7_10_WO_SOFT, Version.fromString("ES 7.10"))
        );
    }

    @ParameterizedTest
    @MethodSource("provideSnapshots")
    public void ReadDocuments_AsExpected(TestResources.Snapshot snapshot, Version version) throws Exception {
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
        var reader = LuceneDocumentsReader.getFactory(sourceResourceProvider).apply(luceneDir);

        Flux<RfsLuceneDocument> documents = reader.readDocuments()
            .sort(Comparator.comparing(doc -> doc.id)); // Sort for consistent order given LuceneDocumentsReader may interleave

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String expectedId = "complexdoc";
            String actualId = doc.id;

            String expectedSource = "{\"title\":\"This is a doc with complex history\",\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "unchangeddoc";
            String actualId = doc.id;

            String expectedSource = "{\"title\":\"This doc will not be changed\\nIt has multiple lines of text\\nIts source doc has extra newlines.\",\"content\":\"bluh bluh\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "updateddoc";
            String actualId = doc.id;

            String expectedSource = "{\"title\":\"This is doc that will be updated\",\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectComplete().verify();
    }

    @Test
    public void ReadDocuments_ES5_Origin_AsExpected() throws Exception {
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
        var reader = LuceneDocumentsReader.getFactory(sourceResourceProvider).apply(luceneDir);

        Flux<RfsLuceneDocument> documents = reader.readDocuments()
            .sort(Comparator.comparing(doc -> doc.id)); // Sort for consistent order given LuceneDocumentsReader may interleave

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String expectedId = "type1#complexdoc";
            String actualId = doc.id;

            String expectedSource = "{\"title\":\"This is a doc with complex history. Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "type2#unchangeddoc";
            String actualId = doc.id;

            String expectedSource = "{\"content\":\"This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
            return true;
        }).expectNextMatches(doc -> {
            String expectedId = "type2#updateddoc";
            String actualId = doc.id;

            String expectedSource = "{\"content\":\"Updated!\"}";
            String actualSource = doc.source;
            assertDocsEqual(expectedId, actualId, expectedSource, actualSource);
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
        LuceneDocumentsReader reader = new LuceneDocumentsReader(Paths.get("dummy"), false, "dummy_field") {
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
        var expectedConcurrentSegments = 5;
        var expectedConcurrentDocReads = 100;
        assertNotNull(actualDocuments);
        assertEquals(numSegments * docsPerSegment, actualDocuments.size());
        assertEquals(expectedConcurrentDocReads, observedConcurrentDocReads.get(), "Expected concurrent document reads to equal DEFAULT_BOUNDED_ELASTIC_SIZE");
        assertEquals(expectedConcurrentSegments, observedConcurrentSegments.get(), "Expected concurrent open segments equal to 5");


    }

    protected void assertDocsEqual(String expectedId, String actualId, String expectedSource, String actualSource) {
        try {
            String sanitizedExpected = expectedSource.trim().replace("\n", "").replace("\\n", "");
            String sanitizedActual = actualSource.trim().replace("\n", "").replace("\\n", "");


            JsonNode expectedNode = objectMapper.readTree(sanitizedExpected);
            JsonNode actualNode = objectMapper.readTree(sanitizedActual);
            assertEquals(expectedId, actualId);
            assertEquals(expectedNode, actualNode);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
