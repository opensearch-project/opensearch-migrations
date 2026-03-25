package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;
import java.util.Map;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.cluster.ClusterReader;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * ClusterReader that reads Solr metadata from a backup directory.
 * Used by the metadata migration to avoid requiring a live Solr cluster.
 */
@Slf4j
public class SolrSnapshotReader implements ClusterReader {
    private final Version version;
    private final Path backupDir;
    private final Map<String, JsonNode> schemas;

    public SolrSnapshotReader(Version version, Path backupDir, Map<String, JsonNode> schemas) {
        this.version = version;
        this.backupDir = backupDir;
        this.schemas = schemas;
        log.info("Created SolrSnapshotReader for {} collection(s) from {}", schemas.size(), backupDir);
    }

    @Override
    public boolean compatibleWith(Version version) {
        return version.getFlavor() == Flavor.SOLR;
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return compatibleWith(version);
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new SolrGlobalMetadataFactory();
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new SolrBackupIndexMetadataFactory(backupDir, schemas);
    }

    @Override
    public String getFriendlyTypeName() {
        return "Solr Backup Snapshot";
    }
}
