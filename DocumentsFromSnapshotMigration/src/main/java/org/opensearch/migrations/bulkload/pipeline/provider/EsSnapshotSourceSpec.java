package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    public EsSnapshotSourceSpec {
        SpecJson.requireNonBlank(repoUri, "repoUri");
        SpecJson.requireNonBlank(snapshotName, "snapshotName");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
    }

    @Override
    public String kind() {
        return EsSnapshotSourceProvider.KIND;
    }

    /** True when both delta inputs are set; a lone one is rejected by the CLI before we get here. */
    @JsonIgnore
    public boolean isDeltaRead() {
        return previousSnapshotName != null && deltaMode != null;
    }

    /** The config form of this spec, which {@code parseSpec} binds back. */
    public ObjectNode toJson() {
        return SpecJson.toJson(this);
    }
}
