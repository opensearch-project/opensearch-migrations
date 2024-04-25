package com.rfs.common;


import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilesystemRepo implements SourceRepo {
    private final Path repoRootDir;

    private Path findRepoFile() throws IOException {
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
        }
        return highestVersionedFile;
    }

    public FilesystemRepo(Path repoRootDir) {
        this.repoRootDir = repoRootDir;
    }

    public Path getRepoRootDir() {
        return repoRootDir;
    }

    public Path getSnapshotRepoDataFilePath() throws IOException {
        return findRepoFile();
    }

    public Path getGlobalMetadataFilePath(String snapshotId) throws IOException {
        String filePath = getRepoRootDir().toString() + "/meta-" + snapshotId + ".dat";
        return Path.of(filePath);
    }

    public Path getSnapshotMetadataFilePath(String snapshotId) throws IOException {
        String filePath = getRepoRootDir().toString() + "/snap-" + snapshotId + ".dat";
        return Path.of(filePath);
    }

    public Path getIndexMetadataFilePath(String indexId, String indexFileId) throws IOException {
        String filePath = getRepoRootDir().toString() + "/indices/" + indexId + "/meta-" + indexFileId + ".dat";
        return Path.of(filePath);
    }

    public Path getShardDirPath(String indexId, int shardId) throws IOException {
        String shardDirPath = getRepoRootDir().toString() + "/indices/" + indexId + "/" + shardId;
        return Path.of(shardDirPath);
    }

    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) throws IOException {
        Path shardDirPath = getShardDirPath(indexId, shardId);
        Path filePath = shardDirPath.resolve("snap-" + snapshotId + ".dat");
        return filePath;
    }

    public Path getBlobFilePath(String indexId, int shardId, String blobName) throws IOException {
        Path shardDirPath = getShardDirPath(indexId, shardId);
        Path filePath = shardDirPath.resolve(blobName);
        return filePath;
    }

    public void prepBlobFiles(ShardMetadata.Data shardMetadata) throws IOException {
        // No work necessary for local filesystem
    }
}
