package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
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

@ToString(onlyExplicitlyIncluded = true)
@Slf4j
public class S3Repo implements SourceRepo {
    private static final double S3_TARGET_THROUGHPUT_GIBPS = 8.0; // Arbitrarily chosen
    private static final long S3_MAX_MEMORY_BYTES = 1024L * 1024 * 1024; // Arbitrarily chosen
    private static final long S3_MINIMUM_PART_SIZE_BYTES = 8L * 1024 * 1024; // Default, but be explicit
    public static final String INDICES_PREFIX_STR = "indices/";

    private final SnapshotFileFinder fileFinder;

    private final Path s3LocalDir;
    @Getter
    @ToString.Include
    private final S3Uri s3RepoUri;
    private final String s3Region;
    private final S3AsyncClient s3Client;

    private int extractVersion(String key, Pattern pattern) {
        Matcher matcher = pattern.matcher(key);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    protected S3Uri findHighestIndexNInS3() {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(s3RepoUri.bucketName)
                .prefix(s3RepoUri.key)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest).join();

        Pattern indexPattern = Pattern.compile("index-(\\d+)$");

        Optional<S3Object> highestVersionedIndexFile = listResponse.contents().stream()
                .filter(s3Object -> indexPattern.matcher(s3Object.key()).find())
                .max(Comparator.comparingInt(s3Object -> extractVersion(s3Object.key(), indexPattern)));

        String rawUri = highestVersionedIndexFile
                .map(s3Object -> "s3://" + s3RepoUri.bucketName + "/" + s3Object.key())
                .orElseThrow(() -> new CannotFindSnapshotRepoRoot(s3RepoUri.bucketName, s3RepoUri.key));

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
            log.atDebug().setMessage("File already exists locally: {}").addArgument(localPath).log();
            return;
        }

        log.atInfo()
            .setMessage("Downloading file from S3: {} to {}").addArgument(s3Uri.uri).addArgument(localPath).log();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(s3Uri.bucketName).key(s3Uri.key).build();

        s3Client.getObject(getObjectRequest, AsyncResponseTransformer.toFile(localPath)).join();
    }

    public static S3Repo create(Path s3LocalDir, S3Uri s3Uri, String s3Region, SnapshotFileFinder finder) {
        return create(s3LocalDir, s3Uri, s3Region, null, finder);
    }

    public static S3Repo create(Path s3LocalDir, S3Uri s3Uri, String s3Region, URI s3Endpoint, SnapshotFileFinder finder) {
        S3AsyncClient s3Client = S3AsyncClient.crtBuilder()
            .region(Region.of(s3Region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .retryConfiguration(r -> r.numRetries(3))
            .targetThroughputInGbps(S3_TARGET_THROUGHPUT_GIBPS)
            .maxNativeMemoryLimitInBytes(S3_MAX_MEMORY_BYTES)
            .minimumPartSizeInBytes(S3_MINIMUM_PART_SIZE_BYTES)
            .endpointOverride(s3Endpoint)
            .build();

        return new S3Repo(s3LocalDir, s3Uri, s3Region, s3Client, finder);
    }

    public S3Repo(Path s3LocalDir, S3Uri s3Uri, String s3Region, S3AsyncClient s3Client, SnapshotFileFinder fileFinder) {
        this.s3LocalDir = s3LocalDir;
        this.s3RepoUri = s3Uri;
        this.s3Region = s3Region;
        this.s3Client = s3Client;
        this.fileFinder = fileFinder;
    }

    @Override
    public Path getRepoRootDir() {
        return s3LocalDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {

        // assuming SnapshotFileFinder handles this correctly per version
        // (ex. SnapshotFileFinder_ES_1_7 always knows to expect just `index` in ES 1.7)
        Path path = fileFinder.getSnapshotRepoDataFilePath();
        if (path != null) {
            return path;
        }
        return (Path) findHighestIndexNInS3();
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getGlobalMetadataFilePath(snapshotId));
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getSnapshotMetadataFilePath(snapshotId));
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fetch(fileFinder.getIndexMetadataFilePath(indexId, indexFileId));
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return fileFinder.getShardDirPath(indexId, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return fetch(fileFinder.getShardMetadataFilePath(snapshotId, indexId, shardId));
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return fetch(fileFinder.getBlobFilePath(indexId, shardId, blobName));
    }

    @Override
    public void prepBlobFiles(ShardMetadata shardMetadata) {
        try (S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3Client).build()) {

            Path shardDirPath = getShardDirPath(shardMetadata.getIndexId(), shardMetadata.getShardId());
            ensureS3LocalDirectoryExists(shardDirPath);

            String blobFilesS3Prefix = s3RepoUri.key
                + INDICES_PREFIX_STR
                + shardMetadata.getIndexId()
                + "/"
                + shardMetadata.getShardId()
                + "/";

            log.atInfo().setMessage("Downloading blob files from S3: s3://{}/{} to {}")
                .addArgument(s3RepoUri.bucketName)
                .addArgument(blobFilesS3Prefix)
                .addArgument(shardDirPath).log();
            DirectoryDownload directoryDownload = transferManager.downloadDirectory(
                DownloadDirectoryRequest.builder()
                    .destination(shardDirPath)
                    .bucket(s3RepoUri.bucketName)
                    .listObjectsV2RequestTransformer(l -> l.prefix(blobFilesS3Prefix))
                    .build()
            );

            // Wait for the transfer to complete
            CompletedDirectoryDownload completedDirectoryDownload = directoryDownload.completionFuture().join();

            log.atInfo().setMessage("Blob file download(s) complete").log();

            // Print out any failed downloads
            completedDirectoryDownload.failedTransfers().forEach(x->log.error("{}", x));
        }
    }

    public static class CannotFindSnapshotRepoRoot extends RfsException {
        public CannotFindSnapshotRepoRoot(String bucket, String prefix) {
            super("Cannot find the snapshot repository root in S3 bucket: " + bucket + ", prefix: " + prefix);
        }
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

    private Path fetch(Path path) {
        ensureFileExistsLocally(makeS3Uri(path), path);
        return path;
    }

    private S3Uri makeS3Uri(Path filePath) {
        if (!filePath.startsWith(s3LocalDir)) {
            throw new IllegalArgumentException("File path must be under s3LocalDir: " + filePath);
        }
        Path relativePath = s3LocalDir.relativize(filePath);
        return new S3Uri(s3RepoUri.uri + "/" + relativePath.toString().replace('\\', '/'));
    }
}
