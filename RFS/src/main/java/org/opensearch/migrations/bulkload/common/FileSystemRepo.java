package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.ToString;

@ToString
public class FileSystemRepo implements SourceRepo {
    private final Path repoRootDir;
    private final SnapshotFileFinder fileFinder;
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    public FileSystemRepo(Path repoRootDir, SnapshotFileFinder fileFinder) {
        this.repoRootDir = repoRootDir;
        this.fileFinder = fileFinder;
    }

    private Path findRepoFile() {
        // The directory may contain multiple of these files, but we want the highest versioned one
        Path highestVersionedFile = null;
        int highestVersion = -1;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoRootDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    Matcher matcher = INDEX_PATTERN.matcher(entry.getFileName().toString());
                    if (matcher.matches()) {
                        int version = Integer.parseInt(matcher.group(1));
                        if (version > highestVersion) {
                            highestVersion = version;
                            highestVersionedFile = entry;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new CantOpenRepoDirectory(e);
        }

        if (highestVersionedFile == null) {
            throw new CantFindRepoIndexFile();
        }
        return highestVersionedFile;
    }

    @Override
    public Path getRepoRootDir() {
        return repoRootDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        Path path = fileFinder.getSnapshotRepoDataFilePath(repoRootDir);
        if (path != null) {
            return path;
        }
        return findRepoFile();
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return fileFinder.getGlobalMetadataFilePath(repoRootDir, snapshotId);
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return fileFinder.getSnapshotMetadataFilePath(repoRootDir, snapshotId);
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fileFinder.getIndexMetadataFilePath(repoRootDir, indexId, indexFileId);
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return fileFinder.getShardDirPath(repoRootDir, indexId, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return fileFinder.getShardMetadataFilePath(repoRootDir, snapshotId, indexId, shardId);
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return fileFinder.getBlobFilePath(repoRootDir, indexId, shardId, blobName);
    }

    @Override
    public void prepBlobFiles(ShardMetadata shardMetadata) {
        // No work necessary for local filesystem
    }

    public static class CantOpenRepoDirectory extends RfsException {
        public CantOpenRepoDirectory(Throwable cause) {
            super("Couldn't open the repo directory for some reason", cause);
        }
    }

    public static class CantFindRepoIndexFile extends RfsException {
        public CantFindRepoIndexFile() {
            super("Can't find the repo index file in the repo directory");
        }
    }
}
