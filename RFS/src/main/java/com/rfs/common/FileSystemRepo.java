package com.rfs.common;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileSystemRepo implements SourceRepo {
    private final Path repoRootDir;

    private Path findRepoFile() {
        // The directory may contain multiple of these files, but we want the highest versioned one
        Pattern pattern = Pattern.compile("^index-(\\d+)$");
        Path highestVersionedFile = null;
        int highestVersion = -1;
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoRootDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    Matcher matcher = pattern.matcher(entry.getFileName().toString());
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

    public FileSystemRepo(Path repoRootDir) {
        this.repoRootDir = repoRootDir;
    }

    @Override
    public Path getRepoRootDir() {
        return repoRootDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        return findRepoFile();
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        String filePath = getRepoRootDir().toString() + "/meta-" + snapshotId + ".dat";
        return Path.of(filePath);
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        String filePath = getRepoRootDir().toString() + "/snap-" + snapshotId + ".dat";
        return Path.of(filePath);
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        String filePath = getRepoRootDir().toString() + "/indices/" + indexId + "/meta-" + indexFileId + ".dat";
        return Path.of(filePath);
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        String shardDirPath = getRepoRootDir().toString() + "/indices/" + indexId + "/" + shardId;
        return Path.of(shardDirPath);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        Path shardDirPath = getShardDirPath(indexId, shardId);
        Path filePath = shardDirPath.resolve("snap-" + snapshotId + ".dat");
        return filePath;
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        Path shardDirPath = getShardDirPath(indexId, shardId);
        Path filePath = shardDirPath.resolve(blobName);
        return filePath;
    }

    @Override
    public void prepBlobFiles(ShardMetadata.Data shardMetadata) {
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
