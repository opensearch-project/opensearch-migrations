package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;
import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Configuration for reading documents out of a Solr backup.
 *
 * @param repoUri          {@code file://} or {@code s3://} backup location
 * @param backupName       the backup/snapshot name under the repo root
 * @param solrMajorVersion source Solr major version; selects the matching Lucene reader
 * @param indexAllowlist   collections to migrate; empty means all
 * @param s3Region         region for an {@code s3://} repo, else null
 * @param endpoint         custom S3 endpoint, else null
 */
public record SolrBackupSourceSpec(
    String repoUri,
    String backupName,
    int solrMajorVersion,
    List<String> indexAllowlist,
    String s3Region,
    String endpoint
) implements DocumentSourceSpec {

    static final String FIELD_REPO_URI = "repoUri";
    static final String FIELD_BACKUP_NAME = "backupName";
    static final String FIELD_SOLR_MAJOR_VERSION = "solrMajorVersion";
    static final String FIELD_INDEX_ALLOWLIST = "indexAllowlist";
    static final String FIELD_S3_REGION = "s3Region";
    static final String FIELD_ENDPOINT = "endpoint";

    public SolrBackupSourceSpec {
        Objects.requireNonNull(repoUri, "repoUri must not be null");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
    }

    @Override
    public String kind() {
        return SolrBackupSourceProvider.KIND;
    }

    /** Serializes back to the config {@link #fromJson} parses; see {@code EsSnapshotSourceSpec#toJson}. */
    public ObjectNode toJson() {
        var node = JsonNodeFactory.instance.objectNode();
        node.put(FIELD_REPO_URI, repoUri);
        SpecJson.putIfPresent(node, FIELD_BACKUP_NAME, backupName);
        node.put(FIELD_SOLR_MAJOR_VERSION, solrMajorVersion);
        var allowlist = node.putArray(FIELD_INDEX_ALLOWLIST);
        indexAllowlist.forEach(allowlist::add);
        SpecJson.putIfPresent(node, FIELD_S3_REGION, s3Region);
        SpecJson.putIfPresent(node, FIELD_ENDPOINT, endpoint);
        return node;
    }

    static SolrBackupSourceSpec fromJson(JsonNode config) {
        return new SolrBackupSourceSpec(
            SpecJson.requiredString(config, FIELD_REPO_URI),
            SpecJson.optionalString(config, FIELD_BACKUP_NAME),
            (int) SpecJson.longOr(config, FIELD_SOLR_MAJOR_VERSION, 0),
            SpecJson.stringList(config, FIELD_INDEX_ALLOWLIST),
            SpecJson.optionalString(config, FIELD_S3_REGION),
            SpecJson.optionalString(config, FIELD_ENDPOINT));
    }
}
