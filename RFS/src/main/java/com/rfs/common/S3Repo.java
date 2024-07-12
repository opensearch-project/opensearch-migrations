package com.rfs.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rfs.models.ShardMetadata;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.DirectoryDownload;
import software.amazon.awssdk.transfer.s3.model.DownloadDirectoryRequest;

public class S3Repo implements SourceRepo {
    private static final Logger logger = LogManager.getLogger(S3Repo.class);
    private static final double S3_TARGET_THROUGHPUT_GIBPS = 8.0; // Arbitrarily chosen
    private static final long S3_MAX_MEMORY_BYTES = 1024L * 1024 * 1024; // Arbitrarily chosen
    private static final long S3_MINIMUM_PART_SIZE_BYTES = 8L * 1024 * 1024; // Default, but be explicit

    private final Path s3LocalDir;
    private final S3Uri s3RepoUri;
    private final String s3Region;
    private final S3AsyncClient s3Client;

    private static int extractVersion(String key) {
        try {
            return Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            throw new CantExtractIndexFileVersion(key, e);
        }
    }

    protected S3Uri findRepoFileUri() {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
            .bucket(s3RepoUri.bucketName)
            .prefix(s3RepoUri.key)
            .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest).join();

        Optional<S3Object> highestVersionedIndexFile = listResponse.contents()
            .stream()
            .filter(s3Object -> s3Object.key().matches(".*index-\\d+$")) // Regex to match index files
            .max(Comparator.comparingInt(s3Object -> extractVersion(s3Object.key())));

        String rawUri = highestVersionedIndexFile.map(s3Object -> "s3://" + s3RepoUri.bucketName + "/" + s3Object.key())
            .orElse("");
        return new S3Uri(rawUri);
    }

    protected void ensureS3LocalDirectoryExists(Path localPath) {
        try {
            Files.createDirectories(localPath);
        } catch (IOException e) {
            throw new CantCreateS3LocalDir(localPath, e);
        }
    }

    protected boolean doesFileExistLocally(Path localPath) {
        return Files.exists(localPath);
    }

    private void ensureFileExistsLocally(S3Uri s3Uri, Path localPath) {
        ensureS3LocalDirectoryExists(localPath.getParent());

        if (doesFileExistLocally(localPath)) {
            logger.debug("File already exists locally: {}", localPath);
            return;
        }

        logger.info("Downloading file from S3: {} to {}", s3Uri.uri, localPath);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(s3Uri.bucketName).key(s3Uri.key).build();

        s3Client.getObject(getObjectRequest, AsyncResponseTransformer.toFile(localPath)).join();
    }

    public static S3Repo create(Path s3LocalDir, S3Uri s3Uri, String s3Region) {
        S3AsyncClient s3Client = S3AsyncClient.crtBuilder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .region(Region.of(s3Region))
            .retryConfiguration(r -> r.numRetries(3))
            .targetThroughputInGbps(S3_TARGET_THROUGHPUT_GIBPS)
            .maxNativeMemoryLimitInBytes(S3_MAX_MEMORY_BYTES)
            .minimumPartSizeInBytes(S3_MINIMUM_PART_SIZE_BYTES)
            .build();

        return new S3Repo(s3LocalDir, s3Uri, s3Region, s3Client);
    }

    public S3Repo(Path s3LocalDir, S3Uri s3Uri, String s3Region, S3AsyncClient s3Client) {
        this.s3LocalDir = s3LocalDir;
        this.s3RepoUri = s3Uri;
        this.s3Region = s3Region;
        this.s3Client = s3Client;
    }

    @Override
    public Path getRepoRootDir() {
        return s3LocalDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        S3Uri repoFileS3Uri = findRepoFileUri();

        String relativeFileS3Uri = repoFileS3Uri.uri.substring(s3RepoUri.uri.length() + 1);

        Path localFilePath = s3LocalDir.resolve(relativeFileS3Uri);
        ensureFileExistsLocally(repoFileS3Uri, localFilePath);

        return localFilePath;
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        String suffix = "meta-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        S3Uri fileUri = new S3Uri(s3RepoUri.uri + "/" + suffix);
        ensureFileExistsLocally(fileUri, filePath);
        return filePath;
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        String suffix = "snap-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        S3Uri fileUri = new S3Uri(s3RepoUri.uri + "/" + suffix);
        ensureFileExistsLocally(fileUri, filePath);
        return filePath;
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        String suffix = "indices/" + indexId + "/meta-" + indexFileId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        S3Uri fileUri = new S3Uri(s3RepoUri.uri + "/" + suffix);
        ensureFileExistsLocally(fileUri, filePath);
        return filePath;
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        String suffix = "indices/" + indexId + "/" + shardId;
        Path shardDirPath = s3LocalDir.resolve(suffix);
        return shardDirPath;
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        String suffix = "indices/" + indexId + "/" + shardId + "/snap-" + snapshotId + ".dat";
        Path filePath = s3LocalDir.resolve(suffix);
        S3Uri fileUri = new S3Uri(s3RepoUri.uri + "/" + suffix);
        ensureFileExistsLocally(fileUri, filePath);
        return filePath;
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        String suffix = "indices/" + indexId + "/" + shardId + "/" + blobName;
        Path filePath = s3LocalDir.resolve(suffix);
        S3Uri fileUri = new S3Uri(s3RepoUri.uri + "/" + suffix);
        ensureFileExistsLocally(fileUri, filePath);
        return filePath;
    }

    @Override
    public void prepBlobFiles(ShardMetadata shardMetadata) {
        S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3Client).build();

        Path shardDirPath = getShardDirPath(shardMetadata.getIndexId(), shardMetadata.getShardId());
        ensureS3LocalDirectoryExists(shardDirPath);

        String blobFilesS3Prefix = s3RepoUri.key
            + "indices/"
            + shardMetadata.getIndexId()
            + "/"
            + shardMetadata.getShardId()
            + "/";

        logger.info(
            "Downloading blob files from S3: s3://%s/%s to %s",
            s3RepoUri.bucketName,
            blobFilesS3Prefix,
            shardDirPath
        );
        DirectoryDownload directoryDownload = transferManager.downloadDirectory(
            DownloadDirectoryRequest.builder()
                .destination(shardDirPath)
                .bucket(s3RepoUri.bucketName)
                .listObjectsV2RequestTransformer(l -> l.prefix(blobFilesS3Prefix))
                .build()
        );

        // Wait for the transfer to complete
        CompletedDirectoryDownload completedDirectoryDownload = directoryDownload.completionFuture().join();

        logger.info("Blob file download(s) complete");

        // Print out any failed downloads
        completedDirectoryDownload.failedTransfers().forEach(logger::error);
    }

    public static class CantCreateS3LocalDir extends RfsException {
        public CantCreateS3LocalDir(Path localPath, Throwable cause) {
            super("Failed to create the S3 local download directory: " + localPath, cause);
        }
    }

    public static class CantExtractIndexFileVersion extends RfsException {
        public CantExtractIndexFileVersion(String key, Throwable cause) {
            super("Failed to extract the Index File version from S3 object key: " + key, cause);
        }
    }
}
