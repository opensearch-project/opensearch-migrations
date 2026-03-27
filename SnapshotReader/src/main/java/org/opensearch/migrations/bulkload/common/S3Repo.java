package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@Slf4j
public class S3Repo implements SourceRepo {
    private static final double S3_TARGET_THROUGHPUT_GIBPS = 8.0; // Arbitrarily chosen
    private static final long S3_MAX_MEMORY_BYTES = 1024L * 1024 * 1024; // Arbitrarily chosen
    private static final long S3_MINIMUM_PART_SIZE_BYTES = 8L * 1024 * 1024; // Default, but be explicit

    public static final String INDICES_PREFIX_STR = "indices/";
    private final Path s3LocalDir;
    private final S3AsyncClient s3Client;
    private final SnapshotFileFinder fileFinder;
    private final String s3Region;

    @Getter
    private final S3Uri s3RepoUri;

    @Override
    public String toString() {
        return String.format("S3Repo [uri=%s, region=%s]", s3RepoUri.uri, s3Region);
    }

    protected void ensureS3LocalDirectoryExists(Path localPath) {
        try {
            if (localPath != null) {
                Files.createDirectories(localPath);
            }
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

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Uri.bucketName)
                .key(s3Uri.key)
                .build();

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
            Path repoDataPath = fileFinder.getSnapshotRepoDataFilePath(s3LocalDir, filesInRoot);
            return fetch(repoDataPath);
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
        Path absS3LocalDir = s3LocalDir.toAbsolutePath().normalize();
        Path absFilePath = filePath.toAbsolutePath().normalize();

        String s3LocalDirStr = absS3LocalDir.toString();
        String filePathStr = absFilePath.toString();

        if (!filePathStr.startsWith(s3LocalDirStr)) {
            throw new IllegalArgumentException("File path must be under s3LocalDir: " + filePath);
        }

        String relativePathStr = filePathStr.substring(s3LocalDirStr.length());
        if (relativePathStr.startsWith(java.io.File.separator)) {
            relativePathStr = relativePathStr.substring(1);
        }

        relativePathStr = relativePathStr.replace('\\', '/');

        String baseUri = s3RepoUri.uri.endsWith("/")
            ? s3RepoUri.uri.substring(0, s3RepoUri.uri.length() - 1)
            : s3RepoUri.uri;

        String fullUri = relativePathStr.isEmpty()
            ? baseUri
            : baseUri + "/" + relativePathStr;

        return new S3Uri(fullUri);
    }

    protected List<String> listFilesInS3Root() {
        // Normalise the repository prefix and remove trailing “/” if present
        String prefixKey = s3RepoUri.key;
        if (prefixKey.endsWith("/")) {
            prefixKey = prefixKey.substring(0, prefixKey.length() - 1);
        }

        String listPrefix = prefixKey.isEmpty() ? null : prefixKey + "/";

        var listRequest = ListObjectsV2Request.builder()
            .bucket(s3RepoUri.bucketName)
            .prefix(listPrefix)   // null = list files from whole bucket
            .delimiter("/")       // keep only the top-level objects
            .build();

        ListObjectsV2Response response;
        try {
            response = s3Client.listObjectsV2(listRequest).join();
        } catch (CompletionException e) {
            throw new CannotListObjectsInS3(s3RepoUri.bucketName, prefixKey, e);
        }

        if (response.contents().isEmpty()) {
            throw new CannotFindSnapshotRepoRoot(s3RepoUri.bucketName, prefixKey);
        }

        List<String> strippedKeys = response.contents().stream()
            .map(S3Object::key)
            .map(key -> key.substring((listPrefix == null ? 0 : listPrefix.length())))
            .map(k -> k.startsWith("/") ? k.substring(1) : k)
            .filter(k -> !k.isEmpty())
            .filter(k -> !k.contains("/"))
            .toList();

        log.atDebug()
            .setMessage("From S3Repo: top-level files under {} -> {}")
            .addArgument(s3RepoUri)
            .addArgument(strippedKeys)
            .log();

        return strippedKeys;
    }

    public static class CannotListObjectsInS3 extends RfsException {
        public CannotListObjectsInS3(String bucket, String prefix, Throwable cause) {
            super("Failed to list objects in S3 bucket: " + bucket + ", prefix: " + prefix, cause);
        }
    }

}
