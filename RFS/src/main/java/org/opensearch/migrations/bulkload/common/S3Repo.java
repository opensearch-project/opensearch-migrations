package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    protected S3Repo(Path s3LocalDir, S3Uri s3Uri, String s3Region, S3AsyncClient s3Client, SnapshotFileFinder fileFinder) {
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
        List<String> filesInRoot = listFilesInS3Root(); // no dirs, only files
        try {
            return fileFinder.getSnapshotRepoDataFilePath(s3LocalDir, filesInRoot);
        } catch (BaseSnapshotFileFinder.CannotFindRepoIndexFile e) {
            throw new CannotFindSnapshotRepoRoot(s3RepoUri.bucketName, s3RepoUri.key);
        }
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getGlobalMetadataFilePath(s3LocalDir, snapshotId));
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getSnapshotMetadataFilePath(s3LocalDir, snapshotId));
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fetch(fileFinder.getIndexMetadataFilePath(s3LocalDir, indexId, indexFileId));
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return fileFinder.getShardDirPath(s3LocalDir, indexId, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return fetch(fileFinder.getShardMetadataFilePath(s3LocalDir, snapshotId, indexId, shardId));
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return fetch(fileFinder.getBlobFilePath(s3LocalDir, indexId, shardId, blobName));
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

    private Path fetch(Path path) {
        ensureFileExistsLocally(makeS3Uri(path), path);
        return path;
    }

    protected S3Uri makeS3Uri(Path filePath) {
        if (!filePath.startsWith(s3LocalDir)) {
            throw new IllegalArgumentException("File path must be under s3LocalDir: " + filePath);
        }
        Path relativePath = s3LocalDir.relativize(filePath);
        String baseUri = s3RepoUri.uri.endsWith("/") ?
                s3RepoUri.uri.substring(0, s3RepoUri.uri.length() - 1) :
                s3RepoUri.uri;
        String fullUri = relativePath.toString().isEmpty()
                ? baseUri
                : baseUri + "/" + relativePath.toString().replace('\\', '/');
        return new S3Uri(fullUri);
    }

    protected List<String> listFilesInS3Root() {
        String debugprefixKey = s3RepoUri.key;
        if (debugprefixKey.endsWith("/")) {
            debugprefixKey = debugprefixKey.substring(0, debugprefixKey.length() - 1);
        }
        System.out.println("DEBUG: s3RepoUri.key (normalized) = >" + debugprefixKey + "<");

        String prefix = s3RepoUri.key;
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
            .bucket(s3RepoUri.bucketName)
            .prefix(prefix.isEmpty() ? null : prefix)
            .delimiter("/")
            .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest).join();

        List<String> rawKeys = listResponse.contents().stream()
                .map(S3Object::key)
                .toList();
        System.out.println("======= S3Repo: Raw S3 keys from prefix: " + s3RepoUri + " =======");
        rawKeys.forEach(System.out::println);
        System.out.println("======= END RAW S3 KEYS =======");

        final String stripPrefix = (debugprefixKey == null || debugprefixKey.isEmpty()) ? "" : debugprefixKey + "/";
        List<String> strippedKeys = rawKeys.stream()
            .map(key -> {
                String out = key;
                if (!stripPrefix.isEmpty() && out.startsWith(stripPrefix)) {
                    out = out.substring(stripPrefix.length());
                }
                // Remove leading slash if one somehow remains
                if (out.startsWith("/")) {
                    out = out.substring(1);
                }
                System.out.println("DEBUG: after strip = '" + out + "'");
                return out;
            })
            .toList();

        log.atInfo().setMessage("S3Repo: Full file list under S3 prefix '{}': {}")
            .addArgument(s3RepoUri)
            .addArgument(strippedKeys)
            .log();

        return strippedKeys;
    }
}
