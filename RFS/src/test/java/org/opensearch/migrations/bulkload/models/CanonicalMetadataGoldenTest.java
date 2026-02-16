package org.opensearch.migrations.bulkload.models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden file tests for canonical metadata. Validates that the canonical normalization
 * produces stable, version-agnostic output from version-specific snapshots.
 *
 * Golden files are stored in test-resources/golden/ as pretty-printed JSON.
 * To regenerate: delete the golden file and run the test — it will write the current output.
 */
@Slf4j
public class CanonicalMetadataGoldenTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final Path GOLDEN_DIR = Paths.get(System.getProperty("user.dir"))
        .resolve("test-resources/golden");

    static Stream<Arguments> indexMetadataTestCases() {
        return Stream.of(
            Arguments.of("ES 7.10", TestResources.SNAPSHOT_ES_7_10_BWC_CHECK, "bwc_index_1", "es710-bwc-index.json"),
            Arguments.of("ES 7.10", TestResources.SNAPSHOT_ES_7_10_BWC_CHECK, "fwc_index_1", "es710-fwc-index.json"),
            Arguments.of("ES 6.8", TestResources.SNAPSHOT_ES_6_8, "test_updates_deletes", "es68-index.json"),
            Arguments.of("ES 5.6", TestResources.SNAPSHOT_ES_5_6, "test_updates_deletes", "es56-index.json")
        );
    }

    static Stream<Arguments> globalMetadataTestCases() {
        return Stream.of(
            Arguments.of("ES 7.10", TestResources.SNAPSHOT_ES_7_10_BWC_CHECK, "es710-global.json"),
            Arguments.of("ES 6.8", TestResources.SNAPSHOT_ES_6_8, "es68-global.json"),
            Arguments.of("ES 5.6", TestResources.SNAPSHOT_ES_5_6, "es56-global.json")
        );
    }

    @ParameterizedTest(name = "{0} index {2}")
    @MethodSource("indexMetadataTestCases")
    void canonicalIndexMetadataMatchesGolden(
        String versionStr, TestResources.Snapshot snapshot, String indexName, String goldenFile
    ) throws Exception {
        var version = Version.fromString(versionStr);
        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(version, true);
        var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var reader = ClusterProviderRegistry.getSnapshotReader(version, repo, false);

        var indexMetadata = reader.getIndexMetadata().fromRepo(snapshot.name, indexName);
        var canonical = CanonicalIndexMetadata.fromIndexMetadata(indexMetadata);

        var actualJson = MAPPER.writeValueAsString(MAPPER.valueToTree(canonical));
        assertMatchesGolden(goldenFile, actualJson);
    }

    @ParameterizedTest(name = "{0} global metadata")
    @MethodSource("globalMetadataTestCases")
    void canonicalGlobalMetadataMatchesGolden(
        String versionStr, TestResources.Snapshot snapshot, String goldenFile
    ) throws Exception {
        var version = Version.fromString(versionStr);
        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(version, true);
        var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var reader = ClusterProviderRegistry.getSnapshotReader(version, repo, false);

        var globalMetadata = reader.getGlobalMetadata().fromRepo(snapshot.name);
        var canonical = CanonicalGlobalMetadata.fromGlobalMetadata(globalMetadata);

        var actualJson = MAPPER.writeValueAsString(MAPPER.valueToTree(canonical));
        assertMatchesGolden(goldenFile, actualJson);
    }

    private void assertMatchesGolden(String goldenFile, String actualJson) throws IOException {
        Path goldenPath = GOLDEN_DIR.resolve(goldenFile);

        if (!Files.exists(goldenPath)) {
            // Generate golden file on first run
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath, actualJson + "\n");
            log.info("Generated golden file: {}", goldenPath);
            return; // First run — no assertion, just generate
        }

        String expectedJson = Files.readString(goldenPath).strip();
        assertEquals(expectedJson, actualJson,
            "Canonical output for " + goldenFile + " does not match golden file. " +
            "If the change is intentional, delete the golden file and re-run to regenerate.");
    }
}
