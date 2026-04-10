package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;



public class FileSystemRepo implements SourceRepo {
    private final Path repoRootDir;
    private final SnapshotFileFinder fileFinder;

    public FileSystemRepo(Path repoRootDir, SnapshotFileFinder fileFinder) {
        this.repoRootDir = repoRootDir;
        this.fileFinder = fileFinder;
    }

    @Override
    public String toString() {
        return String.format("FileSystemRepo [path=%s]", repoRootDir.toAbsolutePath());
    }

    @Override
    public Path getRepoRootDir() {
        return repoRootDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        List<String> filesInRoot = listFilesInFsRoot();
        return fileFinder.getSnapshotRepoDataFilePath(repoRootDir, filesInRoot);
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

    public static class CantOpenRepoDirectory extends RfsException {
        public CantOpenRepoDirectory(Path repoRootDir, Throwable cause) {
            super("Failed to open repository directory: " + repoRootDir.toAbsolutePath(), cause);
        }
    }

    protected List<String> listFilesInFsRoot() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoRootDir)) {
            return StreamSupport.stream(stream.spliterator(), false)
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new CantOpenRepoDirectory(repoRootDir, e);
        }
    }
}
