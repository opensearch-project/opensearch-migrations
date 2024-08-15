package com.rfs.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rfs.common.TestResources.Snapshot;
import com.rfs.models.ShardMetadata;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class LuceneDocumentsReaderTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("test-temp-dir");
        log.atDebug().log("Temporary directory created at: " + tempDirectory);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDirectory)
            .sorted((path1, path2) -> path2.compareTo(path1)) // Delete files before directories
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
    void ReadDocuments_ES_7_10_AsExpected(Snapshot snapshot) {
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
