package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceLocation;

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

    public SolrBackupSourceSpec {
        SpecJson.requireNonBlank(repoUri, "repoUri");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
    }

    @Override
    public String kind() {
        return SolrBackupSourceProvider.KIND;
    }

    @Override
    public SourceLocation location() {
        return new SourceLocation(backupName, repoUri, s3Region);
    }

    /** The config form of this spec, which {@code parseSpec} binds back. */
    public ObjectNode toJson() {
        return SpecJson.toJson(this);
    }
}
