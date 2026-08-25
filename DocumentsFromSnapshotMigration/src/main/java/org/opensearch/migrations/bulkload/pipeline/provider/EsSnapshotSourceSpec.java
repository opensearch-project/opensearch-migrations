package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;
import java.util.Objects;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Configuration for reading documents out of an Elasticsearch/OpenSearch snapshot repository.
 *
 * @param repoUri                    {@code file://}, {@code s3://} or {@code gs://} repository URI
 * @param snapshotName               the snapshot to read
 * @param version                    source cluster version, selecting the snapshot format readers
 * @param indexAllowlist             indices to migrate; empty means all
 * @param s3Region                   region for an {@code s3://} repo, else null
 * @param endpoint                   custom S3/GCS endpoint, else null
 * @param allowLooseVersionMatches   accept a reader whose version only loosely matches the source
 * @param maxShardSizeBytes          reject a shard larger than this; 0 disables the check
 * @param useRecoverySource          treat {@code _recovery_source} as {@code _source} when present
 * @param emitDocType                carry the ES {@code _type} through to the sink
 * @param previousSnapshotName       previous snapshot for a delta read, else null
 * @param deltaMode                  which delta changes to emit, else null
 * @param enableSourcelessMigrations reconstruct {@code _source} for indices that disabled it
 */
public record EsSnapshotSourceSpec(
    String repoUri,
    String snapshotName,
    Version version,
    List<String> indexAllowlist,
    String s3Region,
    String endpoint,
    boolean allowLooseVersionMatches,
    long maxShardSizeBytes,
    boolean useRecoverySource,
    boolean emitDocType,
    String previousSnapshotName,
    DeltaMode deltaMode,
    boolean enableSourcelessMigrations
) implements DocumentSourceSpec {

    static final String FIELD_REPO_URI = "repoUri";
    static final String FIELD_SNAPSHOT_NAME = "snapshotName";
    static final String FIELD_VERSION = "version";
    static final String FIELD_INDEX_ALLOWLIST = "indexAllowlist";
    static final String FIELD_S3_REGION = "s3Region";
    static final String FIELD_ENDPOINT = "endpoint";
    static final String FIELD_ALLOW_LOOSE_VERSION_MATCHES = "allowLooseVersionMatches";
    static final String FIELD_MAX_SHARD_SIZE_BYTES = "maxShardSizeBytes";
    static final String FIELD_USE_RECOVERY_SOURCE = "useRecoverySource";
    static final String FIELD_EMIT_DOC_TYPE = "emitDocType";
    static final String FIELD_PREVIOUS_SNAPSHOT_NAME = "previousSnapshotName";
    static final String FIELD_DELTA_MODE = "deltaMode";
    static final String FIELD_ENABLE_SOURCELESS_MIGRATIONS = "enableSourcelessMigrations";

    public EsSnapshotSourceSpec {
        Objects.requireNonNull(repoUri, "repoUri must not be null");
        Objects.requireNonNull(snapshotName, "snapshotName must not be null");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
    }

    @Override
    public String kind() {
        return EsSnapshotSourceProvider.KIND;
    }

    /** True when both delta inputs are set; a lone one is rejected by the CLI before we get here. */
    public boolean isDeltaRead() {
        return previousSnapshotName != null && deltaMode != null;
    }

    /**
     * Serializes back to the config {@link #fromJson} parses. The caller builds a typed spec and
     * hands the JSON to whichever provider the registry resolved; this round-trip is their contract.
     */
    public ObjectNode toJson() {
        var node = JsonNodeFactory.instance.objectNode();
        node.put(FIELD_REPO_URI, repoUri);
        node.put(FIELD_SNAPSHOT_NAME, snapshotName);
        SpecJson.putIfPresent(node, FIELD_VERSION, version == null ? null : version.toString());
        var allowlist = node.putArray(FIELD_INDEX_ALLOWLIST);
        indexAllowlist.forEach(allowlist::add);
        SpecJson.putIfPresent(node, FIELD_S3_REGION, s3Region);
        SpecJson.putIfPresent(node, FIELD_ENDPOINT, endpoint);
        node.put(FIELD_ALLOW_LOOSE_VERSION_MATCHES, allowLooseVersionMatches);
        node.put(FIELD_MAX_SHARD_SIZE_BYTES, maxShardSizeBytes);
        node.put(FIELD_USE_RECOVERY_SOURCE, useRecoverySource);
        node.put(FIELD_EMIT_DOC_TYPE, emitDocType);
        SpecJson.putIfPresent(node, FIELD_PREVIOUS_SNAPSHOT_NAME, previousSnapshotName);
        SpecJson.putIfPresent(node, FIELD_DELTA_MODE, deltaMode == null ? null : deltaMode.name());
        node.put(FIELD_ENABLE_SOURCELESS_MIGRATIONS, enableSourcelessMigrations);
        return node;
    }

    static EsSnapshotSourceSpec fromJson(JsonNode config) {
        var rawVersion = SpecJson.optionalString(config, FIELD_VERSION);
        var rawDeltaMode = SpecJson.optionalString(config, FIELD_DELTA_MODE);
        return new EsSnapshotSourceSpec(
            SpecJson.requiredString(config, FIELD_REPO_URI),
            SpecJson.requiredString(config, FIELD_SNAPSHOT_NAME),
            rawVersion == null ? null : Version.fromString(rawVersion),
            SpecJson.stringList(config, FIELD_INDEX_ALLOWLIST),
            SpecJson.optionalString(config, FIELD_S3_REGION),
            SpecJson.optionalString(config, FIELD_ENDPOINT),
            SpecJson.booleanOr(config, FIELD_ALLOW_LOOSE_VERSION_MATCHES, false),
            SpecJson.longOr(config, FIELD_MAX_SHARD_SIZE_BYTES, 0L),
            SpecJson.booleanOr(config, FIELD_USE_RECOVERY_SOURCE, false),
            SpecJson.booleanOr(config, FIELD_EMIT_DOC_TYPE, false),
            SpecJson.optionalString(config, FIELD_PREVIOUS_SNAPSHOT_NAME),
            rawDeltaMode == null ? null : DeltaMode.valueOf(rawDeltaMode),
            SpecJson.booleanOr(config, FIELD_ENABLE_SOURCELESS_MIGRATIONS, false));
    }
}
