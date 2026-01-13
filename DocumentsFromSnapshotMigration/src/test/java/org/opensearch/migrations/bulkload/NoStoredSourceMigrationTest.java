package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    enum VersionRange {
        ES_1_TO_4,    // ES 1.x - 4.x (string type)
        ES_2_PLUS,    // ES 2.x+ (boolean/ip/geo_point doc_values support)
        ES_5_PLUS,    // ES 5.x+ (text/keyword)
        ALL           // All versions
    }

    record FieldTypeConfig(
        String sourceType,
        String targetType,
        Object testValue,
        boolean supportsDocValues,
        VersionRange availability
    ) {}

    private static final List<FieldTypeConfig> FIELD_TYPES = List.of(
        // String (not_analyzed) for ES 1.x-4.x, maps to keyword on target
        new FieldTypeConfig("string", "keyword", "test_str", true, VersionRange.ES_1_TO_4),
        // Keyword - ES 5.x+ only
        new FieldTypeConfig("keyword", "keyword", "test_kw", true, VersionRange.ES_5_PLUS),
        // Boolean - ES 2.x+ supports doc_values
        new FieldTypeConfig("boolean", "boolean", true, true, VersionRange.ES_2_PLUS),
        // Integer/Long - work correctly with doc_values
        new FieldTypeConfig("integer", "integer", 42, true, VersionRange.ALL),
        new FieldTypeConfig("long", "long", 9999L, true, VersionRange.ALL)
        // TODO: float/double doc_values return raw bit representation - needs conversion
        // TODO: date doc_values return epoch millis - needs format conversion  
        // TODO: text stored fields not being recovered - needs investigation
        // TODO: IP stored fields need special handling
    );

    /** Field storage permutations - _source is disabled, so we test recovery via doc_values */
    enum Perm {
        DV_STORE(true, true),
        DV_NOSTORE(true, false),
        NODV_STORE(false, true),
        NODV_NOSTORE(false, false);

        final boolean hasDv, hasStore;
        Perm(boolean hasDv, boolean hasStore) {
            this.hasDv = hasDv; this.hasStore = hasStore;
        }
        boolean isRecoverable() { return hasDv || hasStore; }
    }

    static Stream<Arguments> versionPairs() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.OS_V1_3_16, SearchClusterContainer.OS_V2_19_1),
            Arguments.of(SearchClusterContainer.OS_V2_19_1, SearchClusterContainer.OS_V2_19_1)
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
        return UnboundVersionMatchers.isBelowES_7_X.test(version.getVersion());
    }

    /** Text fields don't support doc_values */
    private static boolean isValidCombo(FieldTypeConfig cfg, Perm p) {
        return cfg.supportsDocValues || !p.hasDv;
    }

    private static String fieldName(FieldTypeConfig cfg, Perm p) {
        return cfg.targetType + "_" + p.name().toLowerCase();
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

            StringBuilder props = new StringBuilder();
            StringBuilder docFields = new StringBuilder();
            List<FieldTypeConfig> activeTypes = new ArrayList<>();

            for (var config : FIELD_TYPES) {
                if (!isTypeAvailable(config, sourceVersion)) continue;
                activeTypes.add(config);

                for (var p : Perm.values()) {
                    if (!isValidCombo(config, p)) continue;

                    String fieldName = fieldName(config, p);
                    props.append("\"").append(fieldName).append("\": {\"type\": \"").append(config.sourceType).append("\"");
                    // String type in ES 1.x/2.x needs index: not_analyzed to behave like keyword
                    if (config.sourceType.equals("string")) props.append(", \"index\": \"not_analyzed\"");
                    if (!p.hasDv && config.supportsDocValues) props.append(", \"doc_values\": false");
                    if (p.hasStore) props.append(", \"store\": true");
                    props.append("},\n");

                    String jsonValue = config.testValue instanceof String 
                        ? "\"" + config.testValue + "\"" 
                        : config.testValue.toString();
                    docFields.append("\"").append(fieldName).append("\": ").append(jsonValue).append(",\n");
                }
            }

            String indexBody;
            if (needsDocType(sourceVersion)) {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"%s\":{\"_source\":{\"enabled\":false},\"properties\":{%s}}}}",
                    docType, props.substring(0, props.length() - 2)
                );
            } else {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"_source\":{\"enabled\":false},\"properties\":{%s}}}",
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

            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            StringBuilder targetProps = new StringBuilder();
            for (var config : activeTypes) {
                for (var p : Perm.values()) {
                    if (!isValidCombo(config, p)) continue;
                    String fieldName = fieldName(config, p);
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

            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);
            JsonNode source = root.path("hits").path("hits").get(0).path("_source");

            log.info("Migrated _source: {}", source);

            // ES 1.x doc_values reconstruction not supported - only stored fields work
            boolean docValuesSupported = !VersionMatchers.isES_1_X.test(sourceVersion.getVersion());

            for (var config : activeTypes) {
                for (var p : Perm.values()) {
                    if (!isValidCombo(config, p)) continue;

                    String fieldName = fieldName(config, p);
                    JsonNode fieldValue = source.get(fieldName);

                    boolean shouldRecover = p.hasStore || (p.hasDv && docValuesSupported);
                    if (shouldRecover) {
                        assertEquals(true, fieldValue != null, fieldName + " should be recovered");
                    } else {
                        assertNull(fieldValue, fieldName + " should NOT be recovered");
                    }
                }
            }
        }
    }
}
