package org.opensearch.migrations.bulkload;

import java.io.File;
import java.math.BigInteger;
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
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Matrix end-to-end test: for each (source ES/OS version, target OS version) pair, creates an
 * index with _source disabled (or partially included) populated across the full catalog of field
 * types and storage permutations (doc_values, stored, neither), snapshots the source, runs the
 * sourceless migration pipeline against a live target cluster, and asserts each recovered field
 * matches expectations. Exercises the real LuceneLeafReader Points/Terms/DocValues/Stored recovery
 * paths that SourceReconstructor relies on.
 */
@Tag("isolatedTest")
@Slf4j
public class NoStoredSourceMigrationTest extends SourceTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum VersionRange {
        ES_1_TO_4,    // ES 1.x - 4.x (string type)
        ES_2_PLUS,    // ES 2.x+ (boolean/ip/geo_point doc_values support)
        ES_5_PLUS,    // ES 5.x+ (text/keyword)
        ES_7_PLUS,    // ES 7.x+ (date_nanos)
        ES_7_11_PLUS, // ES 7.11+ (X-Pack features: constant_keyword, wildcard)
        OS_1_PLUS,    // OpenSearch 1.x+ (flat_object)
        OS_2_8_PLUS,  // OpenSearch 2.8+ (unsigned_long)
        ALL           // All versions
    }

    record FieldTypeConfig(
        String fieldName,
        String sourceType,
        String targetType,
        Object testValue,
        VersionRange availability,
        boolean supportsDocValues,
        boolean supportsPoints,
        boolean recoverable,
        Map<String, Object> extraProps
    ) {
        FieldTypeConfig(String fieldName, String sourceType, String targetType, Object testValue,
                       VersionRange availability, boolean supportsDocValues, boolean supportsPoints, Map<String, Object> extraProps) {
            this(fieldName, sourceType, targetType, testValue, availability, supportsDocValues, supportsPoints, true, extraProps);
        }
        FieldTypeConfig(String fieldName, String sourceType, String targetType, Object testValue,
                       VersionRange availability, boolean supportsDocValues, boolean supportsPoints) {
            this(fieldName, sourceType, targetType, testValue, availability, supportsDocValues, supportsPoints, true, Map.of());
        }
        FieldTypeConfig(String fieldName, String sourceType, String targetType, Object testValue,
                       VersionRange availability, boolean supportsDocValues) {
            this(fieldName, sourceType, targetType, testValue, availability, supportsDocValues, false, true, Map.of());
        }
    }

    private static final List<FieldTypeConfig> FIELD_TYPES = List.of(
        new FieldTypeConfig("string", "string", "keyword", "test_str", VersionRange.ES_1_TO_4, true, false, Map.of("index", "not_analyzed")),
        new FieldTypeConfig("keyword", "keyword", "keyword", "test_kw", VersionRange.ES_5_PLUS, true, false),
        new FieldTypeConfig("boolean", "boolean", "boolean", true, VersionRange.ES_2_PLUS, true, false),
        new FieldTypeConfig("boolean_es1", "boolean", "boolean", false, VersionRange.ES_1_TO_4, false, false),
        new FieldTypeConfig("binary", "binary", "binary", "dGVzdA==", VersionRange.ALL, true, false),
        new FieldTypeConfig("integer", "integer", "integer", 42, VersionRange.ALL, true, true),
        new FieldTypeConfig("long", "long", "long", 9999L, VersionRange.ALL, true, true),
        new FieldTypeConfig("float", "float", "float", 3.14f, VersionRange.ES_5_PLUS, true, true),
        new FieldTypeConfig("double", "double", "double", 2.71828, VersionRange.ES_5_PLUS, true, true),
        new FieldTypeConfig("ip", "ip", "ip", "192.168.1.1", VersionRange.ES_2_PLUS, true, true),
        new FieldTypeConfig("ipv6", "ip", "ip", "2001:db8:85a3::8a2e:370:7334", VersionRange.ES_5_PLUS, true, true),
        new FieldTypeConfig("date", "date", "date", "2024-01-15T10:30:00.000Z", VersionRange.ES_2_PLUS, true, true),
        new FieldTypeConfig("date_epoch", "date", "date", 1705315800000L, VersionRange.ES_2_PLUS, true, true, Map.of("format", "epoch_millis")),
        new FieldTypeConfig("scaled_float", "scaled_float", "scaled_float", 123.45, VersionRange.ES_5_PLUS, true, false, Map.of("scaling_factor", 100)),
        new FieldTypeConfig("date_nanos", "date_nanos", "date_nanos", "2024-01-15T10:30:00.123456789Z", VersionRange.ES_7_PLUS, true, true),
        new FieldTypeConfig("geo_point", "geo_point", "geo_point", Map.of("lat", 40.7128, "lon", -74.006), VersionRange.ALL, true, false),
        new FieldTypeConfig("unsigned_long", "unsigned_long", "unsigned_long", 9223372036854775807L, VersionRange.OS_2_8_PLUS, true, false),
        new FieldTypeConfig("unsigned_long_big", "unsigned_long", "unsigned_long", new BigInteger("10000000000000000000"), VersionRange.OS_2_8_PLUS, true, false),
        new FieldTypeConfig("byte", "byte", "byte", (byte) 127, VersionRange.ALL, true, true),
        new FieldTypeConfig("short", "short", "short", (short) 32000, VersionRange.ALL, true, true),
        new FieldTypeConfig("half_float", "half_float", "half_float", 1.5f, VersionRange.ES_5_PLUS, true, true),
        new FieldTypeConfig("token_count", "token_count", "token_count", "one two three four five", VersionRange.ES_5_PLUS, true, true, Map.of("analyzer", "standard")),
        new FieldTypeConfig("constant_keyword", "constant_keyword", "constant_keyword", "constant_val", VersionRange.ES_7_11_PLUS, false, false, false, Map.of("value", "constant_val")),
        new FieldTypeConfig("wildcard", "wildcard", "wildcard", "wild*card", VersionRange.ES_7_11_PLUS, true, false)
    );

    /** Field storage permutations - _source is disabled, so we test recovery via doc_values/stored. */
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
            Arguments.of(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V7_17, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.ES_V8_17, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V3_5_0)
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
            case ES_7_PLUS -> !UnboundVersionMatchers.isBelowES_7_X.test(v);
            case ES_7_11_PLUS -> !UnboundVersionMatchers.isBelowES_7_X.test(v) && !VersionMatchers.anyOS.test(v) && v.getMinor() >= 11;
            case OS_1_PLUS -> VersionMatchers.anyOS.test(v);
            case OS_2_8_PLUS -> VersionMatchers.anyOS.test(v) && (v.getMajor() > 2 || (v.getMajor() == 2 && v.getMinor() >= 8));
            case ALL -> true;
        };
    }

    private static boolean needsDocType(ContainerVersion version) {
        return UnboundVersionMatchers.isBelowES_7_X.test(version.getVersion());
    }

    private static boolean isValidCombo(FieldTypeConfig cfg, Perm p) {
        return cfg.supportsDocValues || !p.hasDv;
    }

    private static String fieldName(FieldTypeConfig cfg, Perm p) {
        return cfg.fieldName + "_" + p.name().toLowerCase();
    }

    private static String normalizeIpv6(String ip) {
        try {
            return java.net.InetAddress.getByName(ip).getHostAddress();
        } catch (Exception e) {
            return ip;
        }
    }

    private static String normalizeDate(String date) {
        try {
            var instant = java.time.Instant.parse(date);
            return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
        } catch (Exception e) {
            return date;
        }
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
                    for (var entry : config.extraProps().entrySet()) {
                        props.append(", \"").append(entry.getKey()).append("\": ");
                        if (entry.getValue() instanceof String) {
                            props.append("\"").append(entry.getValue()).append("\"");
                        } else {
                            props.append(entry.getValue());
                        }
                    }
                    boolean skipDvStoreParams = config.sourceType.equals("wildcard") || config.sourceType.equals("constant_keyword");
                    if (!skipDvStoreParams) {
                        if (p.hasDv && config.supportsDocValues) props.append(", \"doc_values\": true");
                        if (!p.hasDv && config.supportsDocValues) props.append(", \"doc_values\": false");
                        if (p.hasStore) props.append(", \"store\": true");
                    }
                    props.append("},\n");

                    String jsonValue;
                    if (config.testValue instanceof String) {
                        jsonValue = "\"" + config.testValue + "\"";
                    } else if (config.testValue instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) config.testValue;
                        if (map.containsKey("lat") && map.containsKey("lon")) {
                            jsonValue = "{\"lat\":" + map.get("lat") + ",\"lon\":" + map.get("lon") + "}";
                        } else {
                            StringBuilder sb = new StringBuilder("{");
                            boolean first = true;
                            for (var entry : map.entrySet()) {
                                if (!first) sb.append(",");
                                sb.append("\"").append(entry.getKey()).append("\":");
                                if (entry.getValue() instanceof String) {
                                    sb.append("\"").append(entry.getValue()).append("\"");
                                } else {
                                    sb.append(entry.getValue());
                                }
                                first = false;
                            }
                            sb.append("}");
                            jsonValue = sb.toString();
                        }
                    } else {
                        jsonValue = config.testValue.toString();
                    }
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
                    targetProps.append("\"").append(fieldName).append("\": {\"type\": \"").append(config.targetType).append("\"");
                    for (var entry : config.extraProps().entrySet()) {
                        if ("index".equals(entry.getKey())) continue;
                        targetProps.append(", \"").append(entry.getKey()).append("\": ");
                        if (entry.getValue() instanceof String) {
                            targetProps.append("\"").append(entry.getValue()).append("\"");
                        } else {
                            targetProps.append(entry.getValue());
                        }
                    }
                    targetProps.append("},\n");
                }
            }
            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{" + targetProps.substring(0, targetProps.length() - 2) + "}}}";
            log.info("Target index body: {}", targetIndexBody);
            targetOps.createIndex(indexName, targetIndexBody);

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                sourceRepo, "snap", List.of(indexName), targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion()
            ));

            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);
            JsonNode hits = root.path("hits").path("hits");
            assertFalse(hits.isEmpty(), "No documents migrated. Response: " + response);
            JsonNode source = hits.get(0).path("_source");

            log.info("Migrated _source: {}", source);

            boolean pointsSupported = !UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());

            for (var config : activeTypes) {
                for (var p : Perm.values()) {
                    if (!isValidCombo(config, p)) continue;

                    String fieldName = fieldName(config, p);
                    JsonNode fieldValue = source.get(fieldName);

                    if (!config.recoverable()) {
                        assertEquals(null, fieldValue, fieldName + " should NOT be recovered (unsupported type)");
                        continue;
                    }

                    boolean canRecoverFromPoints = pointsSupported && config.supportsPoints;
                    boolean isES8Plus = !UnboundVersionMatchers.isBelowES_8_X.test(sourceVersion.getVersion())
                        && !VersionMatchers.anyOS.test(sourceVersion.getVersion());
                    boolean canRecoverFromTerms = config.targetType.equals("boolean") && !isES8Plus;
                    // Un-tokenized string fields (keyword, or ES 1-4 "string" with index:not_analyzed)
                    // store the entire value as a single indexed term, so reconstruction from the
                    // inverted index round-trips losslessly even when doc_values/stored are disabled.
                    boolean canRecoverFromKeywordTerms = config.sourceType.equals("keyword")
                        || config.sourceType.equals("string");
                    // Pre-Points era (ES 1.x/2.x on Lucene 4/5) indexes numeric/ip/date fields as
                    // trie-encoded prefix-coded terms. SourceReconstructor harvests shift==0 terms
                    // and decodes them back to the original value, so these round-trip losslessly
                    // even without doc_values or stored fields.
                    boolean preBkdNumerics = UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());
                    boolean canRecoverFromNumericTerms = preBkdNumerics && config.supportsPoints;
                    boolean alwaysHasDocValues = config.sourceType.equals("wildcard");
                    boolean shouldRecover = p.hasStore || p.hasDv || canRecoverFromPoints
                        || canRecoverFromTerms || canRecoverFromKeywordTerms
                        || canRecoverFromNumericTerms || alwaysHasDocValues;
                    if (shouldRecover) {
                        assertEquals(true, fieldValue != null, fieldName + " should be recovered but was null");

                        String expected = String.valueOf(config.testValue);
                        String actual = fieldValue.isTextual() ? fieldValue.asText() : String.valueOf(
                            fieldValue.isBoolean() ? fieldValue.asBoolean() :
                            fieldValue.isInt() ? fieldValue.asInt() :
                            fieldValue.isLong() ? fieldValue.asLong() : fieldValue.asText()
                        );
                        if (config.sourceType.equals("ip") && expected.contains(":")) {
                            expected = normalizeIpv6(expected);
                            actual = normalizeIpv6(actual);
                        }
                        if ("epoch_millis".equals(config.extraProps().get("format"))) {
                            assertEquals(config.testValue, fieldValue.asLong(), fieldName + " value mismatch");
                            continue;
                        }
                        if (config.sourceType.equals("date") || config.sourceType.equals("date_nanos")) {
                            expected = normalizeDate(expected);
                            actual = normalizeDate(actual);
                        }
                        if (config.sourceType.equals("geo_point")) {
                            assertEquals(true, fieldValue != null, fieldName + " should not be null");
                            continue;
                        }
                        if (config.sourceType.equals("token_count")) {
                            assertEquals(5, fieldValue.asInt(), fieldName + " value mismatch");
                            continue;
                        }
                        if (config.sourceType.equals("scaled_float")) {
                            double expectedVal = ((Number) config.testValue).doubleValue();
                            double actualVal = fieldValue.asDouble();
                            assertEquals(expectedVal, actualVal, 0.01, fieldName + " value mismatch");
                            continue;
                        }
                        if (config.sourceType.equals("unsigned_long")) {
                            BigInteger expectedVal = config.testValue instanceof BigInteger
                                ? (BigInteger) config.testValue
                                : BigInteger.valueOf((Long) config.testValue);
                            BigInteger actualVal = new BigInteger(fieldValue.asText());
                            assertEquals(expectedVal, actualVal, fieldName + " value mismatch");
                            continue;
                        }
                        assertEquals(expected, actual, fieldName + " value mismatch");
                    } else {
                        assertNull(fieldValue, fieldName + " should NOT be recovered");
                    }
                }
            }
        }
    }

    /**
     * Tests mergeWithDocValues() - when _source exists but has excluded fields.
     * This covers the code path where we merge doc_values into existing _source.
     */
    @ParameterizedTest(name = "mergeWithDocValues: {0} -> {1}")
    @MethodSource("versionPairs")
    public void testMergeWithDocValues(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.start();
            targetCluster.start();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String indexName = "merge_docvalues_test";
            String docType = needsDocType(sourceVersion) ? "doc" : null;
            boolean prEs5 = UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());

            String stringFieldType = prEs5 ? "string\", \"index\": \"not_analyzed" : "keyword";

            String indexBody;
            if (needsDocType(sourceVersion)) {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"%s\":{\"_source\":{\"includes\":[\"included_field\"]}," +
                    "\"properties\":{\"included_field\":{\"type\":\"%s\"},\"excluded_field\":{\"type\":\"integer\",\"doc_values\":true}}}}}",
                    docType, stringFieldType
                );
            } else {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"_source\":{\"includes\":[\"included_field\"]}," +
                    "\"properties\":{\"included_field\":{\"type\":\"%s\"},\"excluded_field\":{\"type\":\"integer\",\"doc_values\":true}}}}",
                    stringFieldType
                );
            }

            String doc = "{\"included_field\": \"included_value\", \"excluded_field\": 42}";

            log.info("Source version: {}, Target version: {}", sourceVersion, targetVersion);
            log.info("Index body: {}", indexBody);

            sourceOps.createIndex(indexName, indexBody);
            sourceOps.createDocument(indexName, "1", doc, null, docType);
            sourceOps.post("/_refresh", null);

            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{\"included_field\":{\"type\":\"keyword\"},\"excluded_field\":{\"type\":\"integer\"}}}}";
            targetOps.createIndex(indexName, targetIndexBody);

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                sourceRepo, "snap", List.of(indexName), targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion()
            ));

            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);
            JsonNode hits = root.path("hits").path("hits");
            assertFalse(hits.isEmpty(), "No documents migrated. Response: " + response);
            JsonNode source = hits.get(0).path("_source");

            log.info("Migrated _source: {}", source);

            assertEquals("included_value", source.get("included_field").asText());
            assertEquals(42, source.get("excluded_field").asInt());
        }
    }

    /**
     * Tests that documents with no recoverable fields are skipped.
     * Uses binary field with store:false (no doc_values, no Points, no stored field).
     */
    @ParameterizedTest(name = "unrecoverable: {0} -> {1}")
    @MethodSource("versionPairs")
    public void testUnrecoverableDocumentSkipped(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        if (UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion())) {
            return;
        }

        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.start();
            targetCluster.start();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String indexName = "unrecoverable_test";
            String docType = needsDocType(sourceVersion) ? "doc" : null;

            String indexBody;
            if (needsDocType(sourceVersion)) {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"%s\":{\"_source\":{\"enabled\":false}," +
                    "\"properties\":{\"binary_field\":{\"type\":\"binary\",\"store\":false}}}}}",
                    docType
                );
            } else {
                indexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"_source\":{\"enabled\":false}," +
                    "\"properties\":{\"binary_field\":{\"type\":\"binary\",\"store\":false}}}}";
            }

            String doc = "{\"binary_field\": \"dGVzdA==\"}";  // base64 "test"

            log.info("Source version: {}, Target version: {}", sourceVersion, targetVersion);
            log.info("Index body: {}", indexBody);

            sourceOps.createIndex(indexName, indexBody);
            sourceOps.createDocument(indexName, "1", doc, null, docType);
            sourceOps.post("/_refresh", null);

            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{\"binary_field\":{\"type\":\"binary\"}}}}";
            targetOps.createIndex(indexName, targetIndexBody);

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                sourceRepo, "snap", List.of(indexName), targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion()
            ));

            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);

            int totalHits = root.path("hits").path("total").isInt()
                ? root.path("hits").path("total").asInt()
                : root.path("hits").path("total").path("value").asInt();
            assertEquals(0, totalHits, "Document with unrecoverable fields should be skipped");
        }
    }

    /**
     * Tests mergeWithDocValues() stored field path - when _source exists but has excluded fields
     * that are recovered from stored fields (not doc_values).
     */
    @ParameterizedTest(name = "mergeStoredFields: {0} -> {1}")
    @MethodSource("versionPairs")
    public void testMergeWithStoredFields(ContainerVersion sourceVersion, ContainerVersion targetVersion) throws Exception {
        try (
            var sourceCluster = new SearchClusterContainer(sourceVersion);
            var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.start();
            targetCluster.start();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String indexName = "merge_stored_test";
            String docType = needsDocType(sourceVersion) ? "doc" : null;
            boolean prEs5 = UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());

            String stringFieldType = prEs5 ? "string\", \"index\": \"not_analyzed" : "keyword";

            String indexBody;
            if (needsDocType(sourceVersion)) {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"%s\":{\"_source\":{\"includes\":[\"included_field\"]}," +
                    "\"properties\":{\"included_field\":{\"type\":\"%s\"},\"excluded_field\":{\"type\":\"%s\",\"store\":true,\"doc_values\":false}}}}}",
                    docType, stringFieldType, stringFieldType
                );
            } else {
                indexBody = String.format(
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                    "\"mappings\":{\"_source\":{\"includes\":[\"included_field\"]}," +
                    "\"properties\":{\"included_field\":{\"type\":\"%s\"},\"excluded_field\":{\"type\":\"%s\",\"store\":true,\"doc_values\":false}}}}",
                    stringFieldType, stringFieldType
                );
            }

            String doc = "{\"included_field\": \"included_value\", \"excluded_field\": \"stored_value\"}";

            log.info("Source version: {}, Target version: {}", sourceVersion, targetVersion);
            log.info("Index body: {}", indexBody);

            sourceOps.createIndex(indexName, indexBody);
            sourceOps.createDocument(indexName, "1", doc, null, docType);
            sourceOps.post("/_refresh", null);

            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{\"included_field\":{\"type\":\"keyword\"},\"excluded_field\":{\"type\":\"keyword\"}}}}";
            targetOps.createIndex(indexName, targetIndexBody);

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                sourceRepo, "snap", List.of(indexName), targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion()
            ));

            targetOps.post("/_refresh", null);
            String response = targetOps.get("/" + indexName + "/_search").getValue();
            JsonNode root = MAPPER.readTree(response);
            JsonNode hits = root.path("hits").path("hits");
            assertFalse(hits.isEmpty(), "No documents migrated. Response: " + response);
            JsonNode source = hits.get(0).path("_source");

            log.info("Migrated _source: {}", source);

            assertEquals("included_value", source.get("included_field").asText());
            assertEquals("stored_value", source.get("excluded_field").asText());
        }
    }
}
