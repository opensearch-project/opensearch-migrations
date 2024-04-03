package com.rfs.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Comparator;
import java.util.Optional;

public class S3Repo implements SourceRepo {
    private static final Logger logger = LogManager.getLogger(S3Repo.class);

    private final Path s3LocalDir;
    private final String s3RepoUri;
    private final String s3Region;
    private final S3Client s3Client;

    private static int extractVersion(String key) {
        try {
            return Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to extract version from S3 object key: " + key, e);
        }
    }

    private String findRepoFileUri() {
        String bucketName = s3RepoUri.split("/")[2];
        String prefix = s3RepoUri.split(bucketName + "/")[1];

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

        Optional<S3Object> highestVersionedIndexFile = listResponse.contents().stream()
                .filter(s3Object -> s3Object.key().matches(".*/index-\\d+$")) // Regex to match index files
                .max(Comparator.comparingInt(s3Object -> extractVersion(s3Object.key())));

        return highestVersionedIndexFile
                .map(s3Object -> "s3://" + bucketName + "/" + s3Object.key())
                .orElse("No index files found in the specified directory.");
    }

    private void downloadFile(String s3Uri, Path localPath) throws IOException {
        logger.info("Downloading file from S3: " + s3Uri + " to " + localPath);
        Files.createDirectories(localPath.getParent());

        String bucketName = s3Uri.split("/")[2];
        String key = s3Uri.split(bucketName + "/")[1];

        s3Client.getObject((req) -> req.bucket(bucketName).key(key), ResponseTransformer.toFile(localPath));
    }

    public S3Repo(Path s3LocalDir, String s3Uri, String s3Region) {
        this.s3LocalDir = s3LocalDir;

        // Remove any trailing slash from the S3 URI
        if (s3Uri.endsWith("/")) {
            this.s3RepoUri = s3Uri.substring(0, s3Uri.length() - 1);
        } else {
            this.s3RepoUri = s3Uri;
        }
        
        this.s3Region = s3Region;
        this.s3Client = S3Client.builder()
                .region(Region.of(this.s3Region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public Path getRepoRootDir() {
        return s3LocalDir;
    }

    public Path getSnapshotRepoDataFilePath() throws IOException {
        String repoFileS3Uri = findRepoFileUri();
        
        // s3://bucket-name/path/to/index-1 => to/index-1
        String relativeFileS3Uri = repoFileS3Uri.substring(s3RepoUri.length() + 1);

        Path localFilePath = s3LocalDir.resolve(relativeFileS3Uri);
        downloadFile(repoFileS3Uri, localFilePath);
        
        return localFilePath;
    }

    public Path getGlobalMetadataFilePath(String snapshotId) throws IOException {
        String suffix = "meta-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        downloadFile(s3RepoUri + "/" + suffix, filePath);
        return filePath;
    }

    public Path getSnapshotMetadataFilePath(String snapshotId) throws IOException {
        String suffix = "snap-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        downloadFile(s3RepoUri + "/" + suffix, filePath);
        return filePath;
    }

    public Path getIndexMetadataFilePath(String indexId, String indexFileId) throws IOException {
        String suffix = "indices/" + indexId + "/meta-" + indexFileId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        downloadFile(s3RepoUri + "/" + suffix, filePath);
        return filePath;
    }

    public Path getShardDirPath(String indexId, int shardId) throws IOException {
        String suffix = "indices/" + indexId + "/" + shardId;
        Path shardDirPath = s3LocalDir.resolve(suffix);
        return shardDirPath;
    }

    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) throws IOException {
        String suffix = "indices/" + indexId + "/" + shardId + "/snap-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        downloadFile(s3RepoUri + "/" + suffix, filePath);
        return filePath;
    }

    public Path getBlobFilePath(String indexId, int shardId, String blobName) throws IOException {
        String suffix = "indices/" + indexId + "/" + shardId + "/" + blobName;
        Path filePath = s3LocalDir.resolve(suffix);
        downloadFile(s3RepoUri + "/" + suffix, filePath);
        return filePath;
    }
}
