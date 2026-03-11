package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test sample file tests for document extraction. Validates that reading documents from
 * pre-built snapshot fixtures produces stable, expected output.
 *
 * Test sample files are stored in RFS/test-resources/test-samples/ as pretty-printed JSON.
 * To regenerate: delete the sample file and run the test — it will write the current output.
 */
@Slf4j
public class GoldenDocumentExtractionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path TEST_SAMPLES_DIR = TestResources.TEST_SAMPLES_DIR;

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("sample-doc-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDirectory)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try { Files.delete(path); } catch (IOException e) {
                    log.atError().setCause(e).setMessage("Failed to delete: {}").addArgument(path).log();
                }
            });
    }

    static Stream<Arguments> documentExtractionTestCases() {
        return Stream.of(
            Arguments.of("ES 5.6", "ES 5.6", TestResources.SNAPSHOT_ES_5_6, "test_updates_deletes", "es56-docs.json"),
            Arguments.of("ES 6.8", "ES 6.8", TestResources.SNAPSHOT_ES_6_8, "test_updates_deletes", "es68-docs.json"),
            Arguments.of("ES 6.8 merged", "ES 6.8", TestResources.SNAPSHOT_ES_6_8_MERGED, "test_updates_deletes", "es68-merged-docs.json"),
            Arguments.of("ES 7.10 w/ soft deletes", "ES 7.10", TestResources.SNAPSHOT_ES_7_10_W_SOFT, "test_updates_deletes", "es710-wsoft-docs.json"),
            Arguments.of("ES 7.10 w/o soft deletes", "ES 7.10", TestResources.SNAPSHOT_ES_7_10_WO_SOFT, "test_updates_deletes", "es710-wosoft-docs.json")
        );
    }

    @ParameterizedTest(name = "{0} documents from {3}")
    @MethodSource("documentExtractionTestCases")
    void extractedDocumentsMatchSample(
        String label, String versionStr, TestResources.Snapshot snapshot, String indexName, String sampleFile
    ) throws Exception {
        var version = Version.fromString(versionStr);
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(version, repo, false);
        var repoAccessor = new SourceRepoAccessor(repo);

        var shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshot.name, indexName, 0);

        Set<ShardFileInfo> filesToUnpack = new TreeSet<>(Comparator.comparing(ShardFileInfo::key));
        filesToUnpack.addAll(shardMetadata.getFiles());

        var unpacker = new SnapshotShardUnpacker.Factory(repoAccessor, tempDirectory)
            .create(filesToUnpack, indexName, shardMetadata.getIndexId(), 0);
        Path luceneDir = unpacker.unpack();

        var reader = new LuceneIndexReader.Factory(sourceResourceProvider).getReader(luceneDir);
        List<LuceneDocumentChange> docs = reader.streamDocumentChanges(shardMetadata.getSegmentFileName())
            .collectList().block();

        String actualJson = serializeDocuments(docs);
        assertMatchesSample(sampleFile, actualJson);
    }

    private String serializeDocuments(List<LuceneDocumentChange> docs) throws Exception {
        ArrayNode array = MAPPER.createArrayNode();
        for (var doc : docs) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", doc.id);
            if (doc.type != null) {
                node.put("type", doc.type);
            }
            node.put("source", new String(doc.source, java.nio.charset.StandardCharsets.UTF_8));
            if (doc.routing != null) {
                node.put("routing", doc.routing);
            }
            node.put("operation", doc.operation.name());
            array.add(node);
        }
        return MAPPER.writeValueAsString(array);
    }

    private void assertMatchesSample(String sampleFile, String actualJson) throws IOException {
        Path samplePath = TEST_SAMPLES_DIR.resolve(sampleFile);

        if (!Files.exists(samplePath)) {
            Files.createDirectories(samplePath.getParent());
            Files.writeString(samplePath, actualJson + "\n");
            log.info("Generated test sample file: {}", samplePath);
            return;
        }

        String expectedJson = Files.readString(samplePath).strip();
        assertEquals(expectedJson, actualJson,
            "Document extraction for " + sampleFile + " does not match test sample file. " +
            "If the change is intentional, delete the sample file and re-run to regenerate.");
    }
}
