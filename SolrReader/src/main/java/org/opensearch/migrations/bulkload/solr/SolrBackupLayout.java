package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
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
     * Falls back to bare {@code zk_backup} (no numeric suffix) for Solr 6/7 backups.
     *
     * @param dirNames list of directory names (not full paths)
     * @return the name of the latest zk_backup directory (e.g. "zk_backup_1"), or null
     */
    public static String findLatestZkBackupName(List<String> dirNames) {
        var numbered = dirNames.stream()
            .filter(name -> ZK_BACKUP_PATTERN.matcher(name).matches())
            .max(Comparator.comparingInt(name -> {
                var m = ZK_BACKUP_PATTERN.matcher(name);
                return m.matches() ? Integer.parseInt(m.group(1)) : -1;
            }))
            .orElse(null);
        if (numbered != null) {
            return numbered;
        }
        // Solr 6/7 fallback: bare zk_backup/ with no numeric suffix
        return dirNames.contains("zk_backup") ? "zk_backup" : null;
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

    /**
     * Counts the shards in a Solr collection backup. Tries three strategies in order so the
     * shard count agrees with what {@link SolrBackupSource} will later read from the same
     * directory:
     * <ol>
     *   <li>SolrCloud incremental: count latest entries in {@code shard_backup_metadata/}.</li>
     *   <li>SolrCloud non-incremental and shard-per-directory layouts: count immediate
     *       subdirectories that contain Lucene segments (either directly, or under
     *       {@code <shard>/data/index/}).</li>
     *   <li>Standalone core / flat backup: a single shard.</li>
     * </ol>
     *
     * @param collectionDir the resolved data directory for the collection
     * @return shard count, always &ge; 1
     */
    public static int countShards(Path collectionDir) {
        if (collectionDir == null || !Files.isDirectory(collectionDir)) {
            return 1;
        }
        var metadataDir = collectionDir.resolve("shard_backup_metadata");
        if (Files.isDirectory(metadataDir)) {
            var shardFiles = findLatestShardMetadataFiles(metadataDir);
            if (!shardFiles.isEmpty()) {
                return shardFiles.size();
            }
        }
        try (var entries = Files.list(collectionDir)) {
            long shardDirs = entries
                .filter(Files::isDirectory)
                .filter(SolrBackupLayout::looksLikeShardDir)
                .count();
            if (shardDirs > 0) {
                return (int) shardDirs;
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate shard directories under {}: {}", collectionDir, e.getMessage());
        }
        return 1;
    }

    private static boolean looksLikeShardDir(Path dir) {
        if (containsSegmentsFile(dir)) {
            return true;
        }
        var dataIndex = dir.resolve("data").resolve("index");
        return containsSegmentsFile(dataIndex);
    }

    private static boolean containsSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        } catch (IOException e) {
            return false;
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
                    || name.equals("backup.properties")     // Solr 6/7 marker file
                    || name.startsWith("snapshot.");        // Solr 6 snapshot.shardN dirs
            });
        } catch (IOException e) {
            return false;
        }
    }

    /** Which Solr deployment produced a backup, inferred from its on-disk markers. */
    public enum SolrBackupMode {
        /** SolrCloud Collections-API {@code BACKUP}: zk_backup/, backup.properties, snapshot.shardN/. */
        CLOUD,
        /** Standalone replication-handler {@code command=backup}: a flat {@code snapshot.<name>/} Lucene index. */
        STANDALONE
    }

    /**
     * Describes a backup root that holds a single collection/core's data <em>directly</em>, with no
     * per-collection wrapper directory. This is what real Solr 6/7 backups produce, and what the
     * manual "reshape" step used to paper over:
     *
     * <ul>
     *   <li><b>SolrCloud</b> ({@link SolrBackupMode#CLOUD}): {@code <root>/{backup.properties, snapshot.shardN/, zk_backup/}}
     *       — the collection name is recovered from {@code backup.properties}; {@code dataPath} is {@code ""}
     *       (the data lives at the root).</li>
     *   <li><b>Standalone</b> ({@link SolrBackupMode#STANDALONE}): {@code <root>/snapshot.<name>/<flat Lucene index>}
     *       — nothing in the backup records the core name, so it is derived from the {@code snapshot.<name>}
     *       directory (prefix stripped) unless an override is supplied; {@code dataPath} is {@code "snapshot.<name>"}.
     *       When the root <em>is</em> the flat index (segments at the top level), {@code dataPath} is {@code ""}.</li>
     * </ul>
     *
     * @param mode           which deployment produced the backup
     * @param collectionName the recovered/derived collection (CLOUD) or core (STANDALONE) name; may be
     *                       {@code null} for CLOUD when no name could be read and no override was given
     * @param dataPath       location of the actual backup data relative to the backup root
     *                       ({@code ""} == the root itself)
     */
    public record BareBackupLayout(SolrBackupMode mode, String collectionName, String dataPath) {
        /** Resolves the directory that actually contains the backup data, given the backup root. */
        public Path resolveFrom(Path backupRoot) {
            return dataPath.isEmpty() ? backupRoot : backupRoot.resolve(dataPath);
        }
    }

    /**
     * Classifies a backup root that contains a single collection/core directly (the "bare" layout
     * produced by SolrCloud 6/7 and standalone Solr), so the Migration Assistant can read it without
     * the manual reshape step. Returns {@code null} when {@code backupRoot} is not a bare
     * single-collection backup (e.g. a wrapped multi-collection layout, where each child directory is
     * a collection).
     *
     * <p>The discriminator is the presence of SolrCloud-only markers ({@code zk_backup}/{@code zk_backup_N},
     * {@code backup.properties}/{@code backup_N.properties}, or {@code shard_backup_metadata}). When present
     * the root is read as a SolrCloud collection — its {@code snapshot.shardN/} directories are shards of one
     * collection, never separate collections. When absent, a {@code snapshot.<name>/} index (or flat segments at
     * the root) is read as a standalone core. This is what keeps the two {@code snapshot.*} naming conventions
     * from being confused.
     *
     * @param backupRoot   the directory the backup was written to / uploaded from
     * @param nameOverride explicit collection/core name; when non-null it wins over any recovered/derived name
     * @return the bare layout descriptor, or {@code null} if the root is not a bare single-collection backup
     */
    public static BareBackupLayout classifyBareBackup(Path backupRoot, String nameOverride) {
        if (backupRoot == null || !Files.isDirectory(backupRoot)) {
            return null;
        }
        if (hasCloudMarkersAtRoot(backupRoot)) {
            var name = nameOverride != null ? nameOverride : readCollectionNameFromBackupProperties(backupRoot);
            log.info("Classified bare SolrCloud backup at {} (collection={})", backupRoot, name);
            return new BareBackupLayout(SolrBackupMode.CLOUD, name, "");
        }
        // Standalone replication backup: a flat Lucene index, either directly at the root...
        if (containsSegmentsFile(backupRoot)) {
            var name = nameOverride != null ? nameOverride
                : stripSnapshotPrefix(backupRoot.getFileName().toString());
            log.info("Classified bare standalone backup (flat root) at {} (core={})", backupRoot, name);
            return new BareBackupLayout(SolrBackupMode.STANDALONE, name, "");
        }
        // ...or inside a single snapshot.<name>/ directory.
        var snapshotDir = findStandaloneSnapshotDir(backupRoot);
        if (snapshotDir != null) {
            var dirName = snapshotDir.getFileName().toString();
            var name = nameOverride != null ? nameOverride : stripSnapshotPrefix(dirName);
            log.info("Classified bare standalone backup at {}/{} (core={})", backupRoot, dirName, name);
            return new BareBackupLayout(SolrBackupMode.STANDALONE, name, dirName);
        }
        return null;
    }

    private static boolean hasCloudMarkersAtRoot(Path root) {
        try (var entries = Files.list(root)) {
            return entries.anyMatch(p -> {
                var name = p.getFileName().toString();
                return name.equals("zk_backup")
                    || ZK_BACKUP_PATTERN.matcher(name).matches()
                    || name.equals("shard_backup_metadata")
                    || name.equals("backup.properties")
                    || (name.startsWith("backup_") && name.endsWith(".properties"));
            });
        } catch (IOException e) {
            log.warn("Failed to inspect backup root for SolrCloud markers {}: {}", root, e.getMessage());
            return false;
        }
    }

    /** Finds a {@code snapshot.<name>/} child directory that holds a flat Lucene index, or null. */
    private static Path findStandaloneSnapshotDir(Path root) {
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("snapshot."))
                .filter(SolrBackupLayout::containsSegmentsFile)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            log.warn("Failed to scan for standalone snapshot dir under {}: {}", root, e.getMessage());
            return null;
        }
    }

    private static String stripSnapshotPrefix(String name) {
        return name.startsWith("snapshot.") ? name.substring("snapshot.".length()) : name;
    }

    /**
     * Reads the collection name from a SolrCloud {@code backup.properties} (or the latest
     * {@code backup_N.properties}) under the given directory. Returns {@code null} if no properties
     * file is present or it carries no recognised collection-name key.
     *
     * <p>NOTE: the exact key Solr writes should be confirmed against a real Solr 7 backup; both
     * {@code collection} and {@code collectionName} are accepted here.
     */
    public static String readCollectionNameFromBackupProperties(Path dir) {
        var propsFile = findBackupPropertiesFile(dir);
        if (propsFile == null) {
            return null;
        }
        var props = new Properties();
        try (var in = Files.newInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", propsFile, e.getMessage());
            return null;
        }
        for (var key : List.of("collection", "collectionName")) {
            var value = props.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        log.warn("No collection-name property found in {}", propsFile);
        return null;
    }

    private static Path findBackupPropertiesFile(Path dir) {
        var bare = dir.resolve("backup.properties");
        if (Files.isRegularFile(bare)) {
            return bare;
        }
        try (var entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                .filter(p -> {
                    var name = p.getFileName().toString();
                    return name.startsWith("backup_") && name.endsWith(".properties");
                })
                .max(Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Listing-based counterpart to {@link #classifyBareBackup} for S3, where backup data is not yet
     * downloaded and only directory (common-prefix) names are available. Returns a layout whose
     * {@code collectionName} is filled for STANDALONE (from the {@code snapshot.<name>} directory) but
     * left {@code null} for CLOUD — the caller must recover the CLOUD name from {@code backup.properties}
     * or an override. Returns {@code null} for a wrapped (multi-collection) layout.
     */
    public static BareBackupLayout detectBareLayoutFromListing(List<String> rootSubDirs) {
        var hasCloudMarkers = rootSubDirs.stream().anyMatch(n ->
            n.equals("zk_backup")
                || ZK_BACKUP_PATTERN.matcher(n).matches()
                || n.equals("shard_backup_metadata"));
        if (hasCloudMarkers) {
            return new BareBackupLayout(SolrBackupMode.CLOUD, null, "");
        }
        var snapshotDir = rootSubDirs.stream()
            .filter(n -> n.startsWith("snapshot."))
            .findFirst()
            .orElse(null);
        if (snapshotDir != null) {
            return new BareBackupLayout(SolrBackupMode.STANDALONE, stripSnapshotPrefix(snapshotDir), snapshotDir);
        }
        return null;
    }

    /** Joins an S3/relative prefix to a suffix, avoiding a leading {@code /} when the base is empty. */
    public static String joinPrefix(String base, String suffix) {
        return base.isEmpty() ? suffix : base + "/" + suffix;
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
