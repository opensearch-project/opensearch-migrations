package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Discover Solr collection names from a backup directory.
     * A valid collection directory contains backup_0.properties or an index/ subdirectory.
     */
    public static List<String> discoverCollections(Path backupDir) throws IOException {
        var collections = new ArrayList<String>();
        try (var dirs = Files.list(backupDir)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> Files.exists(d.resolve("backup_0.properties"))
                    || Files.exists(d.resolve("index"))
                    || hasSegmentsFile(d))
                .map(p -> p.getFileName().toString())
                .forEach(collections::add);
        }
        log.info("Discovered {} collection(s) in backup dir {}: {}", collections.size(), backupDir, collections);
        return collections;
    }

    private static boolean hasSegmentsFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        } catch (IOException e) {
            return false;
        }
    }

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
        return new SolrBackupIndexMetadataFactory(backupDir, schemas, null, version.getMajor());
    }

    @Override
    public String getFriendlyTypeName() {
        return "Solr Backup Snapshot";
    }
}
