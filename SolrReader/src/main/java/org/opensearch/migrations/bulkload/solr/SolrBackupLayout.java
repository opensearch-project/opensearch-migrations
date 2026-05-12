package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.S3Uri;

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
        var dataDir = resolveCollectionDataDir(collectionDir);
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            log.warn("Collection directory does not exist: {}", collectionDir);
            return null;
        }
        try (var entries = Files.list(dataDir)) {
            var latest = entries
                .filter(Files::isDirectory)
                .filter(p -> ZK_BACKUP_PATTERN.matcher(p.getFileName().toString()).matches())
                .max(Comparator.comparingInt(SolrBackupLayout::extractZkBackupIndex));
            if (latest.isPresent()) {
                log.info("Found latest ZK backup: {}", latest.get().getFileName());
                return latest.get();
            }
        } catch (IOException e) {
            log.warn("Failed to list directories in {}", dataDir, e);
        }
        // Solr 6/7 non-incremental SolrCloud BACKUP writes a bare `zk_backup/` directory
        // (no numeric suffix). Fall back to that if no numbered revisions exist.
        var bareZkBackup = dataDir.resolve("zk_backup");
        if (Files.isDirectory(bareZkBackup)) {
            log.info("Found bare zk_backup directory (Solr 6/7 layout): {}", bareZkBackup);
            return bareZkBackup;
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
                .collect(Collectors.groupingBy(p -> {
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

    /**
     * Resolution of a collection's data prefix when listing subdirectories
     * (typically from an S3 listing). Solr 8 backups produce two possible layouts under
     * {@code <snapshot>/<collection>/}:
     * <ul>
     *   <li>Flat: {@code zk_backup_N/, shard_backup_metadata/, index/} directly under {@code <collection>/}</li>
     *   <li>Two-level: {@code <innerName>/zk_backup_N/, <innerName>/shard_backup_metadata/, <innerName>/index/}
     *       where {@code <innerName>} is often (but not always) the collection name.</li>
     * </ul>
     * <p>This result tells callers where the data lives relative to {@code <collection>/}:
     * the empty string for the flat layout, or {@code "<innerName>"} for the two-level layout.
     */
    public record CollectionDataPrefix(String dataPrefix, String latestZkBackupName) {
        /** Returns {@code <collection>/<dataPrefix>} when dataPrefix is non-empty, else {@code <collection>}. */
        public String joinWith(String collection) {
            return dataPrefix.isEmpty() ? collection : collection + "/" + dataPrefix;
        }
    }

    /**
     * Resolves the data prefix and latest zk_backup_N under {@code <collection>/} by consulting
     * a subdirectory listing function (e.g. S3 listing). Returns {@code null} if no zk_backup is
     * found in either the flat or two-level layout.
     *
     * @param collection the collection name
     * @param listSubDirectories function that, given a path relative to the backup root, returns
     *                           the direct subdirectory names under it (non-recursive)
     * @return the resolved data prefix + latest zk_backup name, or {@code null} if none found
     */
    public static CollectionDataPrefix resolveCollectionDataPrefix(
        String collection, Function<String, List<String>> listSubDirectories
    ) {
        var subDirs = listSubDirectories.apply(collection);
        var flatLatest = findLatestZkBackupName(subDirs);
        if (flatLatest != null) {
            return new CollectionDataPrefix("", flatLatest);
        }
        for (var innerDir : subDirs) {
            var innerSubDirs = listSubDirectories.apply(collection + "/" + innerDir);
            var innerLatest = findLatestZkBackupName(innerSubDirs);
            if (innerLatest != null) {
                log.info("Found zk_backup in two-level layout: {}/{}/{}", collection, innerDir, innerLatest);
                return new CollectionDataPrefix(innerDir, innerLatest);
            }
        }
        return null;
    }

    /**
     * Filesystem counterpart to {@link #resolveCollectionDataPrefix}: given a local
     * collection directory, returns the directory that actually contains the Solr backup
     * data files ({@code shard_backup_metadata/}, {@code index/}, {@code zk_backup_N/},
     * or {@code backup_*.properties}). Descends exactly one level for the Solr 8
     * two-level incremental layout. Returns {@code collectionDir} unchanged if the data
     * appears at the top level, or if no data can be located.
     */
    public static Path resolveCollectionDataDir(Path collectionDir) {
        if (collectionDir == null || !Files.isDirectory(collectionDir)) {
            return collectionDir;
        }
        if (containsBackupDataMarkers(collectionDir)) {
            return collectionDir;
        }
        try (var entries = Files.list(collectionDir)) {
            return entries.filter(Files::isDirectory)
                .filter(SolrBackupLayout::containsBackupDataMarkers)
                .findFirst()
                .orElse(collectionDir);
        } catch (IOException e) {
            log.warn("Failed to resolve collection data dir under {}: {}", collectionDir, e.getMessage());
            return collectionDir;
        }
    }

    private static boolean containsBackupDataMarkers(Path dir) {
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(p -> {
                var name = p.getFileName().toString();
                return name.equals("shard_backup_metadata")
                    || name.equals("index")
                    || name.equals("zk_backup")             // Solr 6/7 non-incremental layout
                    || ZK_BACKUP_PATTERN.matcher(name).matches()
                    || name.startsWith("backup_")
                    || name.equals("backup.properties");    // Solr 6/7 marker file
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Builds the S3 URI where Solr's BACKUP API writes a snapshot:
     * {@code s3://<bucket>/[<subpath>/]<snapshotName>}.
     * Mirrors the path used by the CreateSnapshot step so reader and writer
     * land on the same URI regardless of whether the repo URI includes a subpath.
     *
     * @param repoUri      the repository URI (e.g. {@code s3://my-bucket/dir1})
     * @param snapshotName the Solr backup name (appended as the final path segment)
     */
    public static String buildBackupS3Uri(S3Uri repoUri, String snapshotName) {
        var prefix = (repoUri.key == null || repoUri.key.isEmpty())
            ? ""
            : (repoUri.key.endsWith("/") ? repoUri.key : repoUri.key + "/");
        return "s3://" + repoUri.bucketName + "/" + prefix + snapshotName;
    }
}
