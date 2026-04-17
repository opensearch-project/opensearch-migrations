package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Utilities for navigating the Solr backup directory layout.
 *
 * <p>A Solr Collections API backup produces a directory tree like:
 * <pre>
 *   &lt;backupRoot&gt;/&lt;collection&gt;/
 *     zk_backup_0/configs/&lt;configName&gt;/managed-schema[.xml]
 *     shard_backup_metadata/md_shard1_0.json
 *     backup_0.properties
 *     index/...
 * </pre>
 *
 * <p>When successive backups are taken to the same location (Solr 8.9+ supports this
 * for both incremental and non-incremental modes), additional revisions appear:
 * <pre>
 *     zk_backup_0/   (first backup)
 *     zk_backup_1/   (second backup)
 *     shard_backup_metadata/md_shard1_0.json   (first backup)
 *     shard_backup_metadata/md_shard1_1.json   (second backup)
 *     backup_0.properties
 *     backup_1.properties
 * </pre>
 *
 * <p>This class provides helpers to find the latest revision (highest N suffix).
 */
@Slf4j
public final class SolrBackupLayout {

    private static final Pattern ZK_BACKUP_PATTERN = Pattern.compile("zk_backup_(\\d+)");
    private static final Pattern SHARD_METADATA_PATTERN = Pattern.compile("md_(.+)_(\\d+)\\.json");

    private SolrBackupLayout() {}

    /**
     * Finds the latest {@code zk_backup_N} directory under a collection backup directory.
     * Returns the directory with the highest N, or null if no zk_backup directories exist.
     *
     * @param collectionDir the collection backup directory (e.g. {@code <backupRoot>/<collection>})
     * @return path to the latest zk_backup directory, or null if none found
     */
    public static Path findLatestZkBackup(Path collectionDir) {
        if (!Files.isDirectory(collectionDir)) {
            log.warn("Collection directory does not exist: {}", collectionDir);
            return null;
        }
        try (var entries = Files.list(collectionDir)) {
            var latest = entries
                .filter(Files::isDirectory)
                .filter(p -> ZK_BACKUP_PATTERN.matcher(p.getFileName().toString()).matches())
                .max(Comparator.comparingInt(SolrBackupLayout::extractZkBackupIndex));
            if (latest.isPresent()) {
                log.info("Found latest ZK backup: {}", latest.get().getFileName());
                return latest.get();
            }
        } catch (IOException e) {
            log.warn("Failed to list directories in {}", collectionDir, e);
        }
        return null;
    }

    /**
     * Given a list of directory names (from S3 listing or filesystem), finds the
     * latest {@code zk_backup_N} name.
     *
     * @param dirNames list of directory names (not full paths)
     * @return the name of the latest zk_backup directory (e.g. "zk_backup_1"), or null
     */
    public static String findLatestZkBackupName(List<String> dirNames) {
        return dirNames.stream()
            .filter(name -> ZK_BACKUP_PATTERN.matcher(name).matches())
            .max(Comparator.comparingInt(name -> {
                var m = ZK_BACKUP_PATTERN.matcher(name);
                return m.matches() ? Integer.parseInt(m.group(1)) : -1;
            }))
            .orElse(null);
    }

    /**
     * Finds the latest shard metadata file for each shard in the shard_backup_metadata directory.
     * When successive backups produce {@code md_shard1_0.json} and {@code md_shard1_1.json},
     * this returns only the file with the highest backup index for each shard.
     *
     * @param metadataDir the shard_backup_metadata directory
     * @return list of paths to the latest metadata file per shard, or empty list
     */
    public static List<Path> findLatestShardMetadataFiles(Path metadataDir) {
        if (!Files.isDirectory(metadataDir)) {
            return List.of();
        }
        try (var files = Files.list(metadataDir)) {
            // Group by shard name, keep highest backup index per shard
            var byShardName = files
                .filter(p -> SHARD_METADATA_PATTERN.matcher(p.getFileName().toString()).matches())
                .collect(java.util.stream.Collectors.groupingBy(p -> {
                    var m = SHARD_METADATA_PATTERN.matcher(p.getFileName().toString());
                    m.matches();
                    return m.group(1); // shard name
                }));
            return byShardName.values().stream()
                .map(paths -> paths.stream()
                    .max(Comparator.comparingInt(SolrBackupLayout::extractShardMetadataIndex))
                    .orElseThrow())
                .sorted(Comparator.comparing(Path::getFileName))
                .toList();
        } catch (IOException e) {
            log.warn("Failed to list shard metadata in {}", metadataDir, e);
            return List.of();
        }
    }

    private static int extractZkBackupIndex(Path path) {
        var m = ZK_BACKUP_PATTERN.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int extractShardMetadataIndex(Path path) {
        var m = SHARD_METADATA_PATTERN.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(2)) : -1;
    }
}
