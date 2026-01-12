package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("isolatedTest")
@Slf4j
public class NoStoredSourceMigrationTest extends SourceTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Version ranges for type availability
    enum VersionRange {
        ES_1_TO_4,    // ES 1.x - 4.x (string type)
        ES_2_PLUS,    // ES 2.x+ (boolean doc_values support)
        ES_5_PLUS,    // ES 5.x+ (text/keyword)
        ALL           // All versions
    }

    // Field type configuration
    record FieldTypeConfig(
        String sourceType,      // Type name in source
        String targetType,      // Type name in target (OS)
        Object testValue,       // Test value
        boolean supportsDocValues,
        VersionRange availability,
        Map<String, String> extraSourceProps  // Extra properties for source mapping
    ) {
        FieldTypeConfig(String sourceType, String targetType, Object testValue, boolean supportsDocValues, VersionRange availability) {
            this(sourceType, targetType, testValue, supportsDocValues, availability, Map.of());
        }
    }

    // Field types with version-specific mappings
    private static final List<FieldTypeConfig> FIELD_TYPES = List.of(
        // String types (ES 1.x-4.x) - analyzed becomes text
        new FieldTypeConfig("string", "text", "test_text", false, VersionRange.ES_1_TO_4, Map.of("index", "analyzed")),
        // String types (ES 1.x-4.x) - not_analyzed becomes keyword
        new FieldTypeConfig("string", "keyword", "test_kw", true, VersionRange.ES_1_TO_4, Map.of("index", "not_analyzed")),
        // Modern types (ES 5.x+)
        new FieldTypeConfig("text", "text", "test_text", false, VersionRange.ES_5_PLUS),
        new FieldTypeConfig("keyword", "keyword", "test_kw", true, VersionRange.ES_5_PLUS),
        // Boolean - ES 2.x+ supports doc_values, ES 1.x doesn't
        new FieldTypeConfig("boolean", "boolean", true, true, VersionRange.ES_2_PLUS),
        // Integer/Long - universal
        new FieldTypeConfig("integer", "integer", 42, true, VersionRange.ALL),
        new FieldTypeConfig("long", "long", 9999L, true, VersionRange.ALL)
    );

    // Permutations: [excluded, hasDocValues, hasStore]
    private static final boolean[][] PERMUTATIONS = {
        {false, true, false},   // in_source (default)
        {true, true, true},     // excluded_dv_store
        {true, true, false},    // excluded_dv_nostore
        {true, false, true},    // excluded_nodv_store
        {true, false, false}    // excluded_nodv_nostore (unrecoverable)
    };

    private static final String[] PERM_NAMES = {
        "in_source", "excluded_dv_store", "excluded_dv_nostore",
        "excluded_nodv_store", "excluded_nodv_nostore"
    };

    // Version pairs to test
    static Stream<Arguments> versionPairs() {
        return Stream.of(
            // ES 1.x -> OS 2.x
            Arguments.of(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.OS_V2_19_1),
            // ES 2.x -> OS 2.x
            Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.OS_V2_19_1),
            // ES 5.x -> OS 2.x
            Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V2_19_1),
            // ES 6.x -> OS 2.x
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_1),
            // ES 7.x -> OS 2.x
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1),
            // OS 1.x -> OS 2.x
            Arguments.of(SearchClusterContainer.OS_V1_3_16, SearchClusterContainer.OS_V2_19_1)
        );
    }

    @TempDir
    private File localDirectory;

    private static boolean isTypeAvailable(FieldTypeConfig config, ContainerVersion version) {
        var v = version.getVersion();
        return switch (config.availability) {
            case ES_1_TO_4 -> UnboundVersionMatchers.isBelowES_5_X.test(v);
            case ES_2_PLUS -> !VersionMatchers.isES_1_X.test(v);
            case ES_5_PLUS -> !UnboundVersionMatchers.isBelowES_5_X.test(v);
            case ALL -> true;
        };
    }

    private static boolean needsDocType(ContainerVersion version) {
        // ES 1.x-6.x need document type in mappings
        return UnboundVersionMatchers.isBelowES_7_X.test(version.getVersion());
    }

    private static boolean needsExplicitDocValues(ContainerVersion version) {
        // ES 1.x doesn't have doc_values enabled by default
        return VersionMatchers.isES_1_X.test(version.getVersion());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("versionPairs")
    public void testSourceReconstruction(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.start();
            targetCluster.start();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String indexName = "source_reconstruction_test";
            String docType = needsDocType(sourceVersion) ? "doc" : null;

            // Generate mappings based on source version
            List<String> excludedFields = new ArrayList<>();
            StringBuilder props = new StringBuilder();
            StringBuilder docFields = new StringBuilder();
            List<FieldTypeConfig> activeTypes = new ArrayList<>();

            for (var config : FIELD_TYPES) {
                if (!isTypeAvailable(config, sourceVersion)) continue;
                activeTypes.add(config);

                for (int i = 0; i < PERMUTATIONS.length; i++) {
                    boolean excluded = PERMUTATIONS[i][0];
                    boolean hasDv = PERMUTATIONS[i][1];
                    boolean hasStore = PERMUTATIONS[i][2];

                    // Skip invalid: text/string(analyzed) doesn't support doc_values
                    if (!config.supportsDocValues && hasDv && excluded) continue;
                    if (!config.supportsDocValues && !hasDv && !excluded) continue;

                    String fieldName = config.targetType + "_" + PERM_NAMES[i];
                    if (excluded) excludedFields.add(fieldName);

                    // Build source property
                    props.append("\"").append(fieldName).append("\": {\"type\": \"").append(config.sourceType).append("\"");
                    for (var entry : config.extraSourceProps.entrySet()) {
                        props.append(", \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
                    }
                    // ES 1.x needs explicit doc_values: true (not default)
                    if (hasDv && config.supportsDocValues && needsExplicitDocValues(sourceVersion)) {
                        props.append(", \"doc_values\": true");
                    }
                    if (!hasDv && config.supportsDocValues) props.append(", \"doc_values\": false");
                    if (hasStore) props.append(", \"store\": true");
                    props.append("},\n");

                    // Build doc field
                    String jsonValue = config.testValue instanceof String ? "\"" + config.testValue + "\"" : config.testValue.toString();
                    docFields.append("\"").append(fieldName).append("\": ").append(jsonValue).append(",\n");
                }
            }

            // Build index body based on version
            String indexBody;
            if (needsDocType(sourceVersion)) {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"%s\":{\"_source\":{\"excludes\":%s},\"properties\":{%s}}}}",
                    docType, MAPPER.writeValueAsString(excludedFields),
                    props.substring(0, props.length() - 2)
                );
            } else {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"_source\":{\"excludes\":%s},\"properties\":{%s}}}",
                    MAPPER.writeValueAsString(excludedFields),
                    props.substring(0, props.length() - 2)
                );
            }

            String doc = "{" + docFields.substring(0, docFields.length() - 2) + "}";

            log.info("Source version: {}, Target version: {}", sourceVersion, targetVersion);
            log.info("Index body: {}", indexBody);
            log.info("Document: {}", doc);

            sourceOps.createIndex(indexName, indexBody);
            sourceOps.createDocument(indexName, "1", doc, null, docType);
            sourceOps.post("/_refresh", null);

            // Snapshot and migrate
            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            // Create target index with modern types
            StringBuilder targetProps = new StringBuilder();
            for (var config : activeTypes) {
                for (int i = 0; i < PERMUTATIONS.length; i++) {
                    boolean excluded = PERMUTATIONS[i][0];
                    boolean hasDv = PERMUTATIONS[i][1];
                    if (!config.supportsDocValues && hasDv && excluded) continue;
                    if (!config.supportsDocValues && !hasDv && !excluded) continue;
                    String fieldName = config.targetType + "_" + PERM_NAMES[i];
                    targetProps.append("\"").append(fieldName).append("\": {\"type\": \"").append(config.targetType).append("\"},\n");
                }
            }
            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{" + targetProps.substring(0, targetProps.length() - 2) + "}}}";
            log.info("Target index body: {}", targetIndexBody);
            targetOps.createIndex(indexName, targetIndexBody);

            var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> migrateDocumentsSequentially(
                sourceRepo, "snap", List.of(indexName), targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion(), null
            ));

            // Parse result
            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);
            JsonNode source = root.path("hits").path("hits").get(0).path("_source");

            log.info("Migrated _source: {}", source);

            // Assert each field
            for (var config : activeTypes) {
                for (int i = 0; i < PERMUTATIONS.length; i++) {
                    boolean excluded = PERMUTATIONS[i][0];
                    boolean hasDv = PERMUTATIONS[i][1];
                    boolean hasStore = PERMUTATIONS[i][2];

                    if (!config.supportsDocValues && hasDv && excluded) continue;
                    if (!config.supportsDocValues && !hasDv && !excluded) continue;

                    String fieldName = config.targetType + "_" + PERM_NAMES[i];
                    JsonNode fieldValue = source.get(fieldName);

                    boolean recoverable = !excluded || hasDv || hasStore;
                    String actual = fieldValue != null ? fieldValue.asText() : null;

                    log.info("{}: expected={}, actual={}, recoverable={}",
                        fieldName, config.testValue, actual, recoverable);

                    if (!recoverable) {
                        assertNull(fieldValue, fieldName + " should NOT be recovered");
                    } else {
                        assertEquals(true, fieldValue != null, fieldName + " should be recovered");
                    }
                }
            }
        }
    }
}
