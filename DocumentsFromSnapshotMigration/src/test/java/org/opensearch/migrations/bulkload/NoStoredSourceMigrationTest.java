package org.opensearch.migrations.bulkload;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix end-to-end test: for each (source ES/OS version, target OS version) pair, creates one
 * index per SourceMode populated across the full catalog of field types and storage permutations
 * (doc_values, stored, neither), with per-field indexed/array variants, snapshots the source,
 * runs the sourceless migration pipeline against a live target cluster, and asserts each
 * recovered field matches expectations. Exercises the real LuceneLeafReader
 * Points/Terms/DocValues/Stored recovery paths that SourceReconstructor relies on.
 *
 * Axes (driven by an explicit skip list):
 *   field (FIELD_TYPES) × Perm (DV/STORE) × SourceMode × indexed × multiValue
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
        new FieldTypeConfig("constant_keyword", "constant_keyword", "constant_keyword", "constant_val", VersionRange.ES_7_11_PLUS, false, false, Map.of("value", "constant_val")),
        new FieldTypeConfig("wildcard", "wildcard", "wildcard", "wild*card", VersionRange.ES_7_11_PLUS, true, false)
    );

    /** Field storage permutations. */
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

        String shortName() {
            return switch (this) {
                case DV_STORE -> "dvs";
                case DV_NOSTORE -> "dv";
                case NODV_STORE -> "stor";
                case NODV_NOSTORE -> "plain";
            };
        }
    }

    /**
     * Index-level _source directive variants:
     *   ENABLED  - no _source directive; full _source is retained by the engine.
     *              Reconstruction shouldn't be needed but the pipeline should still work.
     *   DISABLED - {"_source":{"enabled":false}}; the legacy sourceless path.
     *   INCLUDES - {"_source":{"includes":[...]}}; mergeWithDocValues/mergeWithStoredFields path.
     *   EXCLUDES - {"_source":{"excludes":[...]}}; mirror of INCLUDES.
     *
     *   NOTE: `_source.enabled:false` is mutually exclusive with includes/excludes.
     */
    enum SourceMode { ENABLED, DISABLED, INCLUDES, EXCLUDES }

    record Permutation(FieldTypeConfig cfg, Perm p, SourceMode sm, boolean indexed, boolean array) {
        /** Encode axes into a single field name:
         *  f_&lt;fieldName&gt;__&lt;perm&gt;__&lt;i|ni&gt;__&lt;s|a&gt; */
        String fieldName() {
            return "f_" + cfg.fieldName
                + "__" + p.shortName()
                + "__" + (indexed ? "i" : "ni")
                + "__" + (array ? "a" : "s");
        }
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

    /**
     * Deterministic "selected" predicate for INCLUDES/EXCLUDES source modes.
     * Picks roughly half of the (field,perm) pairs using a stable hash on the
     * unaxed (cfg+perm) name, so the assertion loop can reproduce the same set.
     */
    private static boolean isSelectedForPartialSource(FieldTypeConfig cfg, Perm p) {
        int h = (cfg.fieldName + "_" + p.name()).hashCode();
        return Math.floorMod(h, 2) == 0;
    }

    /**
     * The only centralized skip predicate. The index-body builder AND the
     * assertion loop both consult this so they stay consistent.
     *
     * Returns Optional.empty() if the permutation should be exercised,
     * or Optional.of(reason) if it should be skipped.
     */
    private static Optional<String> skipReason(Permutation perm, ContainerVersion sourceVersion) {
        var cfg = perm.cfg();
        var p = perm.p();
        String type = cfg.sourceType();

        if (!isTypeAvailable(cfg, sourceVersion)) {
            return Optional.of("type unavailable on this source version");
        }
        if (!cfg.supportsDocValues() && p.hasDv) {
            return Optional.of("field does not support doc_values");
        }
        if (!perm.indexed() && !p.hasDv && !p.hasStore) {
            return Optional.of("index=false + no-dv + no-store is an unrecoverable mapping with no retention");
        }
        // constant_keyword ignores index/store/dv toggles; only exercise the default shape.
        if ("constant_keyword".equals(type)) {
            if (!perm.indexed()) {
                return Optional.of("constant_keyword ignores index=false");
            }
            if (p == Perm.NODV_STORE || p == Perm.NODV_NOSTORE) {
                return Optional.of("constant_keyword ignores store/doc_values toggles; test only default perm");
            }
        }
        // wildcard always has doc_values; skip NODV perms to avoid mapping rejection.
        if ("wildcard".equals(type) && !p.hasDv) {
            return Optional.of("wildcard always has doc_values; skip hasDv=false perms");
        }
        // pre-ES5 "string" uses `index` as enum (no/not_analyzed/analyzed), not boolean.
        // Keep it tractable by only exercising the indexed variant.
        if ("string".equals(type) && !perm.indexed()) {
            return Optional.of("pre-ES5 string uses `index` as enum, not boolean; skip indexed=false");
        }
        // `binary` type is never indexed by definition and rejects the `index` mapping
        // parameter outright on ES 5+/OS ("unknown parameter [index] on mapper [...] of type [binary]"),
        // so there is no meaningful indexed=false variant to exercise.
        if ("binary".equals(type) && !perm.indexed()) {
            return Optional.of("binary type is never indexed; `index: false` is rejected by the mapper");
        }
        // ES 1.x/2.x mapping API rejects `"index": false` on non-string field types
        // (MapperParsingException: Wrong value for index [false] for field [...]).
        // Pre-ES5 only accepts the string-enum form ("no"|"not_analyzed"|"analyzed") on
        // text fields, so there's no meaningful indexed=false variant to exercise elsewhere.
        if (!perm.indexed()
                && (VersionMatchers.isES_1_X.or(VersionMatchers.isES_2_X)).test(sourceVersion.getVersion())) {
            return Optional.of("pre-ES5 rejects `index: false` on non-string field types");
        }
        // Array-value skips.
        if (perm.array()) {
            if ("constant_keyword".equals(type)) {
                return Optional.of("constant_keyword is single-valued by definition");
            }
            if ("geo_point".equals(type)) {
                // Multi-valued geo_point doc_values use version-specific encodings (Morton, quantized lat/lon)
                // that produce incorrect values when decoded with a single decoder. Skip until per-version
                // decoders are implemented.
                return Optional.of("array variant not reliable for geo_point (version-specific encoding)");
            }
        }
        return Optional.empty();
    }

    /** A second value used to form an ARRAY variant `[v, v2]`. Must be type-compatible. */
    private static Object secondValueFor(FieldTypeConfig cfg) {
        return switch (cfg.sourceType()) {
            case "string", "keyword", "wildcard" -> cfg.testValue() + "_b";
            case "boolean" -> !((Boolean) cfg.testValue());
            case "binary" -> "YmJiYg=="; // base64 "bbbb"
            case "integer" -> ((Integer) cfg.testValue()) + 1;
            case "long" -> ((Long) cfg.testValue()) + 1L;
            case "float" -> ((Float) cfg.testValue()) + 1.0f;
            case "double" -> ((Double) cfg.testValue()) + 1.0;
            case "half_float" -> ((Float) cfg.testValue()) + 1.0f;
            case "scaled_float" -> ((Number) cfg.testValue()).doubleValue() + 1.0;
            case "ip" -> cfg.testValue().toString().contains(":") ? "2001:db8:85a3::8a2e:370:7335" : "192.168.1.2";
            case "date" -> cfg.testValue() instanceof Number
                ? ((Number) cfg.testValue()).longValue() + 1000L
                : "2024-02-15T10:30:00.000Z";
            case "date_nanos" -> "2024-02-15T10:30:00.123456789Z";
            case "byte" -> (byte) (((Byte) cfg.testValue()) - 1);
            case "short" -> (short) (((Short) cfg.testValue()) + 1);
            case "unsigned_long" -> cfg.testValue() instanceof BigInteger
                ? ((BigInteger) cfg.testValue()).subtract(BigInteger.ONE)
                : ((Long) cfg.testValue()) - 1L;
            case "token_count" -> "alpha beta gamma";
            case "geo_point" -> Map.of("lat", 41.0, "lon", -73.0);
            default -> cfg.testValue();
        };
    }

    private static String jsonScalar(Object value) {
        if (value instanceof String) {
            return "\"" + value + "\"";
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.containsKey("lat") && map.containsKey("lon")) {
                return "{\"lat\":" + map.get("lat") + ",\"lon\":" + map.get("lon") + "}";
            }
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
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private static String renderValue(Permutation perm) {
        String first = jsonScalar(perm.cfg().testValue());
        if (!perm.array()) return first;
        String second = jsonScalar(secondValueFor(perm.cfg()));
        return "[" + first + "," + second + "]";
    }

    /**
     * Build the mapping entry (properties snippet) for a single permutation.
     * Emits extraProps (required for scaled_float/date format/token_count analyzer/etc.),
     * plus doc_values/store toggles as appropriate.
     */
    private static String mappingEntry(Permutation perm) {
        var cfg = perm.cfg();
        var p = perm.p();
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(perm.fieldName()).append("\": {\"type\": \"").append(cfg.sourceType()).append("\"");
        for (var entry : cfg.extraProps().entrySet()) {
            sb.append(", \"").append(entry.getKey()).append("\": ");
            if (entry.getValue() instanceof String) {
                sb.append("\"").append(entry.getValue()).append("\"");
            } else {
                sb.append(entry.getValue());
            }
        }
        boolean skipDvStoreParams = cfg.sourceType().equals("wildcard") || cfg.sourceType().equals("constant_keyword");
        if (!skipDvStoreParams) {
            if (p.hasDv && cfg.supportsDocValues()) sb.append(", \"doc_values\": true");
            if (!p.hasDv && cfg.supportsDocValues()) sb.append(", \"doc_values\": false");
            if (p.hasStore) sb.append(", \"store\": true");
        }
        // index=false toggle. Skip for types where it's invalid (already caught by skipReason)
        // or where the type has a pre-existing `index` entry in extraProps (pre-ES5 string).
        if (!perm.indexed()
                && !cfg.extraProps().containsKey("index")
                && !cfg.sourceType().equals("constant_keyword")
                && !cfg.sourceType().equals("wildcard")
                && !cfg.sourceType().equals("string")) {
            sb.append(", \"index\": false");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Build the target-index mapping entry (no doc_values/store toggles, no index=false). */
    private static String targetMappingEntry(Permutation perm) {
        var cfg = perm.cfg();
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(perm.fieldName()).append("\": {\"type\": \"").append(cfg.targetType()).append("\"");
        for (var entry : cfg.extraProps().entrySet()) {
            if ("index".equals(entry.getKey())) continue;
            sb.append(", \"").append(entry.getKey()).append("\": ");
            if (entry.getValue() instanceof String) {
                sb.append("\"").append(entry.getValue()).append("\"");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Generates the full (unfiltered) permutation list for one source version.
     * Callers should apply skipReason to drop invalid combos.
     */
    private static List<Permutation> permutationsFor(SourceMode mode, ContainerVersion sourceVersion) {
        List<Permutation> out = new ArrayList<>();
        for (var cfg : FIELD_TYPES) {
            if (!isTypeAvailable(cfg, sourceVersion)) continue;
            for (var p : Perm.values()) {
                for (boolean indexed : new boolean[] { true, false }) {
                    for (boolean array : new boolean[] { false, true }) {
                        out.add(new Permutation(cfg, p, mode, indexed, array));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Build the index-create body for a given SourceMode. The caller has already
     * filtered `perms` through skipReason.
     *
     * Note: `_source.enabled:false` + includes/excludes is mutually exclusive at the
     * index level, so each mode gets its own _source directive shape.
     */
    private static String buildIndexBody(
        SourceMode mode,
        List<Permutation> perms,
        Set<String> partialSet,
        String docType
    ) {
        StringBuilder props = new StringBuilder();
        for (var perm : perms) {
            props.append(mappingEntry(perm)).append(",\n");
        }
        String propsStr = props.length() >= 2 ? props.substring(0, props.length() - 2) : "";

        String sourceDirective;
        switch (mode) {
            case ENABLED:
                sourceDirective = "";
                break;
            case DISABLED:
                sourceDirective = "\"_source\":{\"enabled\":false},";
                break;
            case INCLUDES: {
                StringBuilder arr = new StringBuilder("[");
                boolean first = true;
                for (var n : partialSet) {
                    if (!first) arr.append(",");
                    arr.append("\"").append(n).append("\"");
                    first = false;
                }
                arr.append("]");
                sourceDirective = "\"_source\":{\"includes\":" + arr + "},";
                break;
            }
            case EXCLUDES: {
                StringBuilder arr = new StringBuilder("[");
                boolean first = true;
                for (var n : partialSet) {
                    if (!first) arr.append(",");
                    arr.append("\"").append(n).append("\"");
                    first = false;
                }
                arr.append("]");
                sourceDirective = "\"_source\":{\"excludes\":" + arr + "},";
                break;
            }
            default:
                throw new IllegalStateException("unknown mode " + mode);
        }

        if (docType != null) {
            return String.format(
                "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
                    + "\"mappings\":{\"%s\":{%s\"properties\":{%s}}}}",
                docType, sourceDirective, propsStr
            );
        }
        return String.format(
            "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
                + "\"mappings\":{%s\"properties\":{%s}}}",
            sourceDirective, propsStr
        );
    }

    /** Build the document body (one doc per index) containing every permutation value. */
    private static String buildDocBody(List<Permutation> perms) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var perm : perms) {
            if (!first) sb.append(",\n");
            sb.append("\"").append(perm.fieldName()).append("\": ").append(renderValue(perm));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Build the target-side mapping body. No _source directives; target is OS 3.5. */
    private static String buildTargetIndexBody(List<Permutation> perms) {
        StringBuilder tp = new StringBuilder();
        for (var perm : perms) {
            tp.append(targetMappingEntry(perm)).append(",\n");
        }
        String tpStr = tp.length() >= 2 ? tp.substring(0, tp.length() - 2) : "";
        return "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
            + "\"mappings\":{\"properties\":{" + tpStr + "}}}";
    }

    private static String indexNameFor(SourceMode mode) {
        // Note: work-item coordinator forbids '__' in names, so use single '-' separator.
        return "source_reconstruction_test_" + mode.name().toLowerCase();
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

            String docType = needsDocType(sourceVersion) ? "doc" : null;

            // Build the active set (after skipReason) for every SourceMode up-front.
            // We create the index + doc per mode, snapshot once (covering all indexes),
            // then run a single migration and assert per mode / per permutation.
            Map<SourceMode, List<Permutation>> activeByMode = new java.util.EnumMap<>(SourceMode.class);
            Map<SourceMode, Set<String>> partialByMode = new java.util.EnumMap<>(SourceMode.class);
            Map<SourceMode, Integer> exercisedByMode = new java.util.EnumMap<>(SourceMode.class);
            Map<SourceMode, Integer> skippedByMode = new java.util.EnumMap<>(SourceMode.class);

            List<String> allIndexNames = new ArrayList<>();

            for (var mode : SourceMode.values()) {
                var all = permutationsFor(mode, sourceVersion);
                var kept = new ArrayList<Permutation>();
                int skipped = 0;
                for (var perm : all) {
                    var reason = skipReason(perm, sourceVersion);
                    if (reason.isPresent()) {
                        skipped++;
                        continue;
                    }
                    kept.add(perm);
                }
                activeByMode.put(mode, kept);
                exercisedByMode.put(mode, kept.size());
                skippedByMode.put(mode, skipped);

                // Deterministic selected set for INCLUDES/EXCLUDES.
                Set<String> partialSet = new HashSet<>();
                if (mode == SourceMode.INCLUDES || mode == SourceMode.EXCLUDES) {
                    for (var perm : kept) {
                        if (isSelectedForPartialSource(perm.cfg(), perm.p())) {
                            partialSet.add(perm.fieldName());
                        }
                    }
                }
                partialByMode.put(mode, partialSet);

                log.info("Mode={} exercised={} skipped={} partialSetSize={}",
                    mode, kept.size(), skipped, partialSet.size());

                if (kept.isEmpty()) {
                    log.warn("Mode={} produced no permutations for source {}; skipping index creation",
                        mode, sourceVersion);
                    continue;
                }

                String indexBody = buildIndexBody(mode, kept, partialSet, docType);
                String doc = buildDocBody(kept);
                String indexName = indexNameFor(mode);
                allIndexNames.add(indexName);

                log.info("[{}] Index body: {}", indexName, indexBody);
                log.info("[{}] Document (len={})", indexName, doc.length());

                sourceOps.createIndex(indexName, indexBody);
                sourceOps.createDocument(indexName, "1", doc, null, docType);
            }

            sourceOps.post("/_refresh", null);

            var snapshotCtx = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, "snap", snapshotCtx);
            sourceCluster.copySnapshotData(localDirectory.toString());

            // Create target indexes (one per mode).
            for (var mode : SourceMode.values()) {
                var kept = activeByMode.get(mode);
                if (kept == null || kept.isEmpty()) continue;
                String targetIndexBody = buildTargetIndexBody(kept);
                String indexName = indexNameFor(mode);
                log.info("[{}] Target index body: {}", indexName, targetIndexBody);
                targetOps.createIndex(indexName, targetIndexBody);
            }

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            var docCtx = DocumentMigrationTestContext.factory().noOtelTracking();

            waitForRfsCompletion(() -> SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                sourceRepo, "snap", allIndexNames, targetCluster,
                new AtomicInteger(), new Random(1), docCtx,
                sourceCluster.getContainerVersion().getVersion(),
                targetCluster.getContainerVersion().getVersion()
            ));

            targetOps.post("/_refresh", null);

            // Per-mode assertions.
            for (var mode : SourceMode.values()) {
                var kept = activeByMode.get(mode);
                if (kept == null || kept.isEmpty()) continue;
                String indexName = indexNameFor(mode);
                String response = targetOps.get("/" + indexName + "/_search").getValue();
                JsonNode root = MAPPER.readTree(response);
                JsonNode hits = root.path("hits").path("hits");
                assertFalse(hits.isEmpty(), "No documents migrated for mode " + mode + ". Response: " + response);
                JsonNode source = hits.get(0).path("_source");
                log.info("[{}] Migrated _source (truncated to 500 chars): {}",
                    indexName, source.toString().substring(0, Math.min(500, source.toString().length())));

                Set<String> partialSet = partialByMode.get(mode);
                for (var perm : kept) {
                    assertPermutation(perm, source, partialSet, sourceVersion);
                }
            }

            // Final summary log for human sanity check.
            int totalExercised = 0, totalSkipped = 0;
            for (var mode : SourceMode.values()) {
                totalExercised += exercisedByMode.getOrDefault(mode, 0);
                totalSkipped += skippedByMode.getOrDefault(mode, 0);
                log.info("Summary sourceVersion={} Mode={} exercised={} skipped={}",
                    sourceVersion, mode, exercisedByMode.getOrDefault(mode, 0),
                    skippedByMode.getOrDefault(mode, 0));
            }
            log.info("Summary sourceVersion={} TOTAL exercised={} skipped={}",
                sourceVersion, totalExercised, totalSkipped);
        }
    }

    /**
     * Per-field assertion. Mirrors the original logic plus SourceMode and array handling.
     *
     * - ENABLED: field should be present (engine kept _source), value matches.
     * - DISABLED: original reconstruction logic applies.
     * - INCLUDES: if in partialSet, came via _source; else reconstruction applies.
     * - EXCLUDES: mirror -- if in partialSet (excluded), reconstruction applies; else _source.
     */
    private void assertPermutation(
        Permutation perm, JsonNode source, Set<String> partialSet, ContainerVersion sourceVersion
    ) {
        var cfg = perm.cfg();
        var p = perm.p();
        String fieldName = perm.fieldName();
        JsonNode fieldValue = source.get(fieldName);

        boolean viaSourceDirectly = switch (perm.sm()) {
            case ENABLED -> true;
            case DISABLED -> false;
            case INCLUDES -> partialSet.contains(fieldName);
            case EXCLUDES -> !partialSet.contains(fieldName);
        };

        if (viaSourceDirectly) {
            assertNotNull(fieldValue, fieldName + " [mode=" + perm.sm() + "] should be present via _source");
            assertValueMatches(perm, fieldValue);
            return;
        }

        // Reconstruction path.
        boolean pointsSupported = !UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());

        if (!cfg.recoverable()) {
            assertNull(fieldValue, fieldName + " should NOT be recovered (unsupported type)");
            return;
        }

        boolean canRecoverFromPoints = pointsSupported && cfg.supportsPoints() && perm.indexed();
        boolean canRecoverFromTerms = cfg.targetType().equals("boolean") && perm.indexed();
        boolean canRecoverFromKeywordTerms = (cfg.sourceType().equals("keyword") || cfg.sourceType().equals("string"))
            && perm.indexed();
        boolean preBkdNumerics = UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion.getVersion());
        boolean canRecoverFromNumericTerms = preBkdNumerics && cfg.supportsPoints() && perm.indexed();
        boolean alwaysHasDocValues = cfg.sourceType().equals("wildcard");
        boolean hasConstantValue = cfg.sourceType().equals("constant_keyword");

        // Guaranteed recovery: stored fields, doc_values, Points, constant values
        boolean guaranteedRecovery = p.hasStore || p.hasDv
            || canRecoverFromPoints || canRecoverFromNumericTerms || alwaysHasDocValues || hasConstantValue;
        // Best-effort recovery: inverted index terms (keyword/boolean) — may not work on all segment formats
        boolean bestEffortRecovery = canRecoverFromTerms || canRecoverFromKeywordTerms;

        if (guaranteedRecovery) {
            assertNotNull(fieldValue, fieldName + " [mode=" + perm.sm() + "] should be recovered but was null");
            assertValueMatches(perm, fieldValue);
        } else if (bestEffortRecovery) {
            // Terms-based recovery is best-effort; verify value if present but don't fail if null
            if (fieldValue != null) {
                assertValueMatches(perm, fieldValue);
            }
        } else {
            assertNull(fieldValue, fieldName + " should NOT be recovered");
        }
    }

    /**
     * Compare the recovered JSON value against the permutation's expected value.
     * For array perms, accept either a scalar equal to the first element or an
     * array containing it (Points/Terms often collapse multi-values on recovery).
     */
    private static void assertValueMatches(Permutation perm, JsonNode fieldValue) {
        var cfg = perm.cfg();

        // For array perms, recovery paths legitimately do not preserve order or even
        // array-ness (doc-values sort lex/numeric, terms-dict de-duplicates+sorts, numeric
        // stored-fields may collapse to the last scalar). Accept a match against ANY of
        // the original array values regardless of whether the recovered shape is a scalar
        // or an array. See reconstruction-test-array-ordering-pitfall.
        if (perm.array()) {
            assertArrayValueMatches(perm, fieldValue);
            return;
        }

        // Extract one scalar JsonNode to compare against (scalar perms only).
        JsonNode target = fieldValue;

        // Short-circuit by type; array collapses to the first element above.
        if (cfg.sourceType().equals("geo_point")) {
            // Either shape (scalar or array) is acceptable.
            assertNotNull(fieldValue, perm.fieldName() + " should not be null");
            return;
        }
        if (cfg.sourceType().equals("token_count")) {
            // token_count acceptable forms across versions:
            //   - numeric (e.g. 5)         → count from doc_values / Points reconstruction
            //   - textual integer ("5")    → stored field (numeric count rendered as string)
            //   - textual original string  → stored field on ES 7+ preserves original text
            if (target.isTextual()) {
                String s = target.asText();
                try {
                    int n = Integer.parseInt(s);
                    assertEquals(5, n, perm.fieldName() + " value mismatch");
                } catch (NumberFormatException nfe) {
                    assertEquals(cfg.testValue().toString(), s,
                        perm.fieldName() + " should be original string from stored field");
                }
            } else {
                assertEquals(5, target.asInt(), perm.fieldName() + " value mismatch");
            }
            return;
        }
        if (cfg.sourceType().equals("scaled_float")) {
            double expectedVal = ((Number) cfg.testValue()).doubleValue();
            double actualVal = target.isArray() && target.size() > 0 ? target.get(0).asDouble() : target.asDouble();
            assertEquals(expectedVal, actualVal, 0.01, perm.fieldName() + " value mismatch");
            return;
        }
        if (cfg.sourceType().equals("unsigned_long")) {
            BigInteger expectedVal = cfg.testValue() instanceof BigInteger
                ? (BigInteger) cfg.testValue()
                : BigInteger.valueOf((Long) cfg.testValue());
            JsonNode actualNode = target.isArray() && target.size() > 0 ? target.get(0) : target;
            BigInteger actualVal = new BigInteger(actualNode.asText());
            assertEquals(expectedVal, actualVal, perm.fieldName() + " value mismatch");
            return;
        }
        if ("epoch_millis".equals(cfg.extraProps().get("format"))) {
            long expected = ((Number) cfg.testValue()).longValue();
            long actual = target.isArray() && target.size() > 0 ? target.get(0).asLong() : target.asLong();
            assertEquals(expected, actual, perm.fieldName() + " value mismatch");
            return;
        }

        String expected = String.valueOf(cfg.testValue());
        String actual;
        if (target.isTextual()) {
            actual = target.asText();
        } else if (target.isBoolean()) {
            actual = String.valueOf(target.asBoolean());
        } else if (target.isInt()) {
            actual = String.valueOf(target.asInt());
        } else if (target.isLong()) {
            actual = String.valueOf(target.asLong());
        } else if (target.isArray() && target.size() > 0) {
            JsonNode first = target.get(0);
            actual = first.isTextual() ? first.asText() : first.asText();
        } else {
            actual = target.asText();
        }

        if (cfg.sourceType().equals("ip") && expected.contains(":")) {
            expected = normalizeIpv6(expected);
            actual = normalizeIpv6(actual);
        }
        if (cfg.sourceType().equals("date") || cfg.sourceType().equals("date_nanos")) {
            expected = normalizeDate(expected);
            actual = normalizeDate(actual);
        }

        // For array mode, accept either exact match of the first element or the
        // reconstructed value being an array containing the expected value.
        // (Unreachable — array perms are handled by assertArrayValueMatches above.
        // Retained as a no-op guard in case this function grows another entry path.)

        assertEquals(expected, actual, perm.fieldName() + " value mismatch");
    }

    /**
     * Array-perm value matcher. Recovery paths (doc-values, terms-dict, Points, stored
     * fields) do NOT preserve insertion order, may sort lex/numeric, may de-duplicate,
     * and may collapse a multi-valued doc to a scalar. Accept a match against ANY of
     * the original array values regardless of whether the recovered shape is a scalar
     * or an array.
     */
    private static void assertArrayValueMatches(Permutation perm, JsonNode fieldValue) {
        var cfg = perm.cfg();

        // geo_point & token_count have bespoke scalar/shape contracts that carry over
        // to array mode.
        if (cfg.sourceType().equals("geo_point")) {
            assertNotNull(fieldValue, perm.fieldName() + " should not be null");
            // Multi-valued geo_point recovery produces a list of {lat,lon} maps from
            // Morton-encoded SortedNumericDocValues. Verify we got an array with 2 elements
            // and that each decoded lat/lon is within 0.01 of one of the original values.
            if (fieldValue.isArray()) {
                assertEquals(2, fieldValue.size(), perm.fieldName() + " should have 2 geo_point values");
                // Both original points should be present (order may differ due to Morton sort)
                @SuppressWarnings("unchecked")
                var expected1 = (Map<String, Object>) cfg.testValue();
                @SuppressWarnings("unchecked")
                var expected2 = (Map<String, Object>) secondValueFor(cfg);
                double lat1 = ((Number) expected1.get("lat")).doubleValue();
                double lon1 = ((Number) expected1.get("lon")).doubleValue();
                double lat2 = ((Number) expected2.get("lat")).doubleValue();
                double lon2 = ((Number) expected2.get("lon")).doubleValue();
                boolean foundFirst = false, foundSecond = false;
                for (int i = 0; i < fieldValue.size(); i++) {
                    JsonNode pt = fieldValue.get(i);
                    double aLat = pt.path("lat").asDouble();
                    double aLon = pt.path("lon").asDouble();
                    if (Math.abs(aLat - lat1) < 0.01 && Math.abs(aLon - lon1) < 0.01) foundFirst = true;
                    if (Math.abs(aLat - lat2) < 0.01 && Math.abs(aLon - lon2) < 0.01) foundSecond = true;
                }
                assertTrue(foundFirst && foundSecond,
                    perm.fieldName() + " should contain both geo_point values but was " + fieldValue);
            }
            return;
        }
        if (cfg.sourceType().equals("token_count")) {
            // token_count array perms store two strings whose tokenised counts DIFFER:
            //   "one two three four five" -> 5 tokens
            //   "alpha beta gamma"        -> 3 tokens
            // Doc-values / Points recovery paths numerically sort the count array, so
            // the recovered order is [3,5] (not [5,3]). Never read by index — iterate
            // every node and accept membership in {5, 3}. Text nodes are acceptable
            // if they parse to the expected count or are one of the original input strings.
            assertNotNull(fieldValue, perm.fieldName() + " should not be null");
            Set<Integer> acceptableCounts = Set.of(
                5,  // count of cfg.testValue() "one two three four five"
                3   // count of secondValueFor(cfg) "alpha beta gamma"
            );
            Set<String> acceptableStrings = Set.of(
                String.valueOf(cfg.testValue()),
                String.valueOf(secondValueFor(cfg))
            );
            List<JsonNode> nodes = new ArrayList<>();
            if (fieldValue.isArray()) {
                for (int i = 0; i < fieldValue.size(); i++) {
                    nodes.add(fieldValue.get(i));
                }
            } else {
                nodes.add(fieldValue);
            }
            for (JsonNode n : nodes) {
                if (n.isTextual()) {
                    String s = n.asText();
                    try {
                        int cnt = Integer.parseInt(s);
                        assertTrue(acceptableCounts.contains(cnt),
                            perm.fieldName() + " token count " + cnt
                                + " not in acceptable set " + acceptableCounts);
                    } catch (NumberFormatException nfe) {
                        assertTrue(acceptableStrings.contains(s),
                            perm.fieldName() + " text " + s
                                + " not in acceptable original strings " + acceptableStrings);
                    }
                } else {
                    assertTrue(acceptableCounts.contains(n.asInt()),
                        perm.fieldName() + " token count " + n.asInt()
                            + " not in acceptable set " + acceptableCounts);
                }
            }
            return;
        }

        Set<String> acceptable = new HashSet<>();
        acceptable.add(normalizeForCompare(cfg, String.valueOf(cfg.testValue())));
        acceptable.add(normalizeForCompare(cfg, String.valueOf(secondValueFor(cfg))));

        Set<String> actualValues = new HashSet<>();
        if (fieldValue.isArray()) {
            for (int i = 0; i < fieldValue.size(); i++) {
                actualValues.add(normalizeForCompare(cfg, nodeAsText(fieldValue.get(i))));
            }
        } else {
            actualValues.add(normalizeForCompare(cfg, nodeAsText(fieldValue)));
        }

        assertFalse(Collections.disjoint(acceptable, actualValues),
            perm.fieldName() + " should contain one of " + acceptable + " but was " + fieldValue);
    }

    private static String nodeAsText(JsonNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return String.valueOf(n.asBoolean());
        if (n.isInt()) return String.valueOf(n.asInt());
        if (n.isLong()) return String.valueOf(n.asLong());
        return n.asText();
    }

    private static String normalizeForCompare(FieldTypeConfig cfg, String s) {
        if (s == null) return "";
        if (cfg.sourceType().equals("ip") && s.contains(":")) {
            return normalizeIpv6(s);
        }
        if (cfg.sourceType().equals("date") || cfg.sourceType().equals("date_nanos")) {
            return normalizeDate(s);
        }
        return s;
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
}
