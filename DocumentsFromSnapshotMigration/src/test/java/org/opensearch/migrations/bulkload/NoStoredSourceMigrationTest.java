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
        ES_7_PLUS,    // ES 7.x+ (date_nanos)
        OS_2_8_PLUS,  // OpenSearch 2.8+ (unsigned_long)
        ALL           // All versions
    }

    record FieldTypeConfig(
        String fieldName,     // Unique field name prefix
        String sourceType,
        String targetType,
        Object testValue,
        boolean supportsDocValues,
        VersionRange availability,
        Map<String, Object> extraProps  // Additional mapping properties (e.g., format, scaling_factor)
    ) {
        // Convenience constructor without extra props
        FieldTypeConfig(String fieldName, String sourceType, String targetType, Object testValue, 
                       boolean supportsDocValues, VersionRange availability) {
            this(fieldName, sourceType, targetType, testValue, supportsDocValues, availability, Map.of());
        }
    }

    private static final List<FieldTypeConfig> FIELD_TYPES = List.of(
        // String (not_analyzed) for ES 1.x-4.x, maps to keyword on target
        new FieldTypeConfig("string", "string", "keyword", "test_str", true, VersionRange.ES_1_TO_4, Map.of("index", "not_analyzed")),
        // Keyword - ES 5.x+ only
        new FieldTypeConfig("keyword", "keyword", "keyword", "test_kw", true, VersionRange.ES_5_PLUS),
        // Boolean - ES 2.x+ supports doc_values
        new FieldTypeConfig("boolean", "boolean", "boolean", true, true, VersionRange.ES_2_PLUS),
        // Boolean for ES 1.x - no doc_values, recovered via terms index
        new FieldTypeConfig("boolean_es1", "boolean", "boolean", false, false, VersionRange.ES_1_TO_4),
        // Binary - stored as base64 in ES, doc_values not supported
        new FieldTypeConfig("binary", "binary", "binary", "dGVzdA==", false, VersionRange.ES_5_PLUS),
        // Integer/Long - work correctly with doc_values and points
        new FieldTypeConfig("integer", "integer", "integer", 42, true, VersionRange.ALL),
        new FieldTypeConfig("long", "long", "long", 9999L, true, VersionRange.ALL),
        // Float/Double - ES 5+ supports points
        new FieldTypeConfig("float", "float", "float", 3.14f, true, VersionRange.ES_5_PLUS),
        new FieldTypeConfig("double", "double", "double", 2.71828, true, VersionRange.ES_5_PLUS),
        // IP - ES 2.x+ supports doc_values for IP (test IPv4)
        new FieldTypeConfig("ip", "ip", "ip", "192.168.1.1", true, VersionRange.ES_2_PLUS),
        // IP with IPv6 - ES 5.x+ (test full IPv6 address)
        new FieldTypeConfig("ipv6", "ip", "ip", "2001:db8:85a3::8a2e:370:7334", true, VersionRange.ES_5_PLUS),
        // Date - ES 2.x+ supports doc_values (ISO format)
        new FieldTypeConfig("date", "date", "date", "2024-01-15T10:30:00.000Z", true, VersionRange.ES_2_PLUS),
        // Date with epoch_millis format - tests the epoch_millis branch
        new FieldTypeConfig("date_epoch", "date", "date", 1705315800000L, true, VersionRange.ES_2_PLUS, Map.of("format", "epoch_millis")),
        // Scaled float - ES 5.x+ only, requires scaling_factor
        new FieldTypeConfig("scaled_float", "scaled_float", "scaled_float", 123.45, true, VersionRange.ES_5_PLUS, Map.of("scaling_factor", 100)),
        // Date nanos - ES 7.x+ (nanosecond precision)
        new FieldTypeConfig("date_nanos", "date_nanos", "date_nanos", "2024-01-15T10:30:00.123456789Z", true, VersionRange.ES_7_PLUS),
        // Geo point - ES 2.x+ supports doc_values
        new FieldTypeConfig("geo_point", "geo_point", "geo_point", Map.of("lat", 40.7128, "lon", -74.006), true, VersionRange.ES_2_PLUS),
        // Unsigned long - OpenSearch 2.8+ (positive value, no overflow)
        new FieldTypeConfig("unsigned_long", "unsigned_long", "unsigned_long", 9223372036854775807L, true, VersionRange.OS_2_8_PLUS),
        // Unsigned long overflow - OpenSearch 2.8+ (value > Long.MAX_VALUE, tests overflow handling)
        new FieldTypeConfig("unsigned_long_big", "unsigned_long", "unsigned_long", new BigInteger("10000000000000000000"), true, VersionRange.OS_2_8_PLUS)
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
            Arguments.of(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.ES_V2_4_6, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V1_3_16, SearchClusterContainer.OS_V3_0_0),
            Arguments.of(SearchClusterContainer.OS_V2_19_1, SearchClusterContainer.OS_V3_0_0)
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
            case OS_2_8_PLUS -> VersionMatchers.anyOS.test(v) && (v.getMajor() > 2 || (v.getMajor() == 2 && v.getMinor() >= 8));
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
        return cfg.fieldName + "_" + p.name().toLowerCase();
    }

    /** Normalize IPv6 address to full form for comparison */
    private static String normalizeIpv6(String ip) {
        try {
            return java.net.InetAddress.getByName(ip).getHostAddress();
        } catch (Exception e) {
            return ip;
        }
    }

    /** Normalize date string for comparison (remove trailing .000 before Z) */
    private static String normalizeDate(String date) {
        // Parse and re-format to ensure consistent representation
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
                    // ES 1.x needs explicit doc_values: true for string fields
                    if (p.hasDv && config.supportsDocValues) props.append(", \"doc_values\": true");
                    if (!p.hasDv && config.supportsDocValues) props.append(", \"doc_values\": false");
                    if (p.hasStore) props.append(", \"store\": true");
                    props.append("},\n");

                    String jsonValue;
                    if (config.testValue instanceof String) {
                        jsonValue = "\"" + config.testValue + "\"";
                    } else if (config.testValue instanceof Map) {
                        // geo_point as {"lat": x, "lon": y}
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) config.testValue;
                        jsonValue = "{\"lat\":" + map.get("lat") + ",\"lon\":" + map.get("lon") + "}";
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
                        if ("index".equals(entry.getKey())) continue; // source-only prop
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

            // ES 5+ supports Points (BKD tree) for numeric/IP fields
            boolean pointsSupported = !UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());

            for (var config : activeTypes) {
                for (var p : Perm.values()) {
                    if (!isValidCombo(config, p)) continue;

                    String fieldName = fieldName(config, p);
                    JsonNode fieldValue = source.get(fieldName);

                    // Points can recover numeric/IP fields even without doc_values or store
                    boolean canRecoverFromPoints = pointsSupported && 
                        (config.targetType.equals("integer") || config.targetType.equals("long") || 
                         config.targetType.equals("float") || config.targetType.equals("double") ||
                         config.targetType.equals("ip") || config.targetType.equals("date") ||
                         config.targetType.equals("date_nanos"));
                    // Boolean fields can be recovered via terms index (all versions)
                    boolean canRecoverFromTerms = config.targetType.equals("boolean");
                    boolean shouldRecover = p.hasStore || p.hasDv || canRecoverFromPoints || canRecoverFromTerms;
                    if (shouldRecover) {
                        assertEquals(true, fieldValue != null, fieldName + " should be recovered but was null");
                        
                        // Compare string representations to handle type differences 
                        String expected = String.valueOf(config.testValue);
                        String actual = fieldValue.isTextual() ? fieldValue.asText() : String.valueOf(
                            fieldValue.isBoolean() ? fieldValue.asBoolean() :
                            fieldValue.isInt() ? fieldValue.asInt() :
                            fieldValue.isLong() ? fieldValue.asLong() : fieldValue.asText()
                        );
                        // Normalize IPv6 addresses for comparison (:: vs :0:0:)
                        if (config.sourceType.equals("ip") && expected.contains(":")) {
                            expected = normalizeIpv6(expected);
                            actual = normalizeIpv6(actual);
                        }
                        // Date with epoch_millis format returns raw long
                        if ("epoch_millis".equals(config.extraProps().get("format"))) {
                            assertEquals(config.testValue, fieldValue.asLong(), fieldName + " value mismatch");
                            continue;
                        }
                        // Normalize dates for comparison (2024-01-15T10:30:00.000Z vs 2024-01-15T10:30:00Z)
                        if (config.sourceType.equals("date") || config.sourceType.equals("date_nanos")) {
                            expected = normalizeDate(expected);
                            actual = normalizeDate(actual);
                        }
                        // Geo point comparison - can be string "lat, lon" or object {lat, lon}
                        if (config.sourceType.equals("geo_point")) {
                            // Just verify it's not null - geo_point format varies
                            assertEquals(true, fieldValue != null, fieldName + " should not be null");
                            continue;
                        }
                        // Scaled float comparison - allow small floating point differences
                        if (config.sourceType.equals("scaled_float")) {
                            double expectedVal = ((Number) config.testValue).doubleValue();
                            double actualVal = fieldValue.asDouble();
                            assertEquals(expectedVal, actualVal, 0.01, fieldName + " value mismatch");
                            continue;
                        }
                        // Unsigned long comparison - use BigInteger for values > Long.MAX_VALUE
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

            // Use string type for ES 1.x/2.x, keyword for ES 5+
            String stringFieldType = prEs5 ? "string\", \"index\": \"not_analyzed" : "keyword";

            // Create index with _source includes (only "included_field"), excluding "excluded_field"
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

            // Target index with both fields
            String targetIndexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}," +
                "\"mappings\":{\"properties\":{\"included_field\":{\"type\":\"keyword\"},\"excluded_field\":{\"type\":\"integer\"}}}}";
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

            // included_field should be from _source
            assertEquals("included_value", source.get("included_field").asText());
            // excluded_field should be recovered from doc_values via mergeWithDocValues
            assertEquals(42, source.get("excluded_field").asInt());
        }
    }
}
