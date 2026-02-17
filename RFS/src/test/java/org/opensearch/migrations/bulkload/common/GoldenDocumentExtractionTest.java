package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;

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
 * Golden file tests for document extraction. Validates that reading documents from
 * pre-built snapshot fixtures produces stable, expected output.
 *
 * Golden files are stored in test-resources/golden/ as pretty-printed JSON.
 * To regenerate: delete the golden file and run the test — it will write the current output.
 */
@Slf4j
public class GoldenDocumentExtractionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path GOLDEN_DIR = Paths.get(System.getProperty("user.dir"))
        .resolve("test-resources/golden");

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("golden-doc-test");
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
    void extractedDocumentsMatchGolden(
        String label, String versionStr, TestResources.Snapshot snapshot, String indexName, String goldenFile
    ) throws Exception {
        var version = Version.fromString(versionStr);
        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(version, true);
        var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var sourceResourceProvider = ClusterProviderRegistry.getSnapshotReader(version, repo, false);
        var repoAccessor = new DefaultSourceRepoAccessor(repo);

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
        assertMatchesGolden(goldenFile, actualJson);
    }

    private String serializeDocuments(List<LuceneDocumentChange> docs) throws Exception {
        ArrayNode array = MAPPER.createArrayNode();
        for (var doc : docs) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", doc.id);
            if (doc.type != null) {
                node.put("type", doc.type);
            }
            node.put("source", doc.source);
            if (doc.routing != null) {
                node.put("routing", doc.routing);
            }
            node.put("operation", doc.operation.name());
            array.add(node);
        }
        return MAPPER.writeValueAsString(array);
    }

    private void assertMatchesGolden(String goldenFile, String actualJson) throws IOException {
        Path goldenPath = GOLDEN_DIR.resolve(goldenFile);

        if (!Files.exists(goldenPath)) {
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath, actualJson + "\n");
            log.info("Generated golden file: {}", goldenPath);
            return;
        }

        String expectedJson = Files.readString(goldenPath).strip();
        assertEquals(expectedJson, actualJson,
            "Document extraction for " + goldenFile + " does not match golden file. " +
            "If the change is intentional, delete the golden file and re-run to regenerate.");
    }
}
