package org.opensearch.migrations.bulkload.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GcsRepo implements SourceRepo {
    private final Path localDir;
    private final Storage storageClient;
    private final SnapshotFileFinder fileFinder;

    @Getter
    private final GcsUri gcsRepoUri;

    @Override
    public String toString() {
        return String.format("GcsRepo [uri=%s]", gcsRepoUri.uri);
    }

    public static GcsRepo create(Path localDir, GcsUri gcsUri, SnapshotFileFinder finder) {
        return create(localDir, gcsUri, null, finder);
    }

    public static GcsRepo create(Path localDir, GcsUri gcsUri, String endpoint, SnapshotFileFinder finder) {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.setHost(endpoint);
            // A custom endpoint means we are talking to a local emulator (e.g.
            // fake-gcs-server) rather than real GCS. Use NoCredentials so the client
            // does not attempt to authenticate via Application Default Credentials,
            // which off-GCE falls back to the GCE metadata server and fails with
            // "ComputeEngineCredentials cannot find the metadata server". On the real
            // GCS path (no endpoint override) credentials are left to the default
            // resolution chain, preserving Workload Identity on GKE.
            builder.setCredentials(NoCredentials.getInstance());
            log.atInfo().setMessage("Using custom GCS endpoint: {}").addArgument(endpoint).log();
        }
        Storage storage = builder.build().getService();
        return new GcsRepo(localDir, gcsUri, storage, finder);
    }

    protected GcsRepo(Path localDir, GcsUri gcsUri, Storage storageClient, SnapshotFileFinder fileFinder) {
        this.localDir = localDir;
        this.gcsRepoUri = gcsUri;
        this.storageClient = storageClient;
        this.fileFinder = fileFinder;
    }

    @Override
    public Path getRepoRootDir() {
        return localDir;
    }

    @Override
    public Path getSnapshotRepoDataFilePath() {
        List<String> filesInRoot = listFilesInRoot();
        try {
            Path repoDataPath = fileFinder.getSnapshotRepoDataFilePath(localDir, filesInRoot);
            return fetch(repoDataPath);
        } catch (BaseSnapshotFileFinder.CannotFindRepoIndexFile e) {
            throw new CannotFindSnapshotRepoRoot(gcsRepoUri.bucketName, gcsRepoUri.key);
        }
    }

    @Override
    public Path getGlobalMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getGlobalMetadataFilePath(localDir, snapshotId));
    }

    @Override
    public Path getSnapshotMetadataFilePath(String snapshotId) {
        return fetch(fileFinder.getSnapshotMetadataFilePath(localDir, snapshotId));
    }

    @Override
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fetch(fileFinder.getIndexMetadataFilePath(localDir, indexId, indexFileId));
    }

    @Override
    public Path getShardDirPath(String indexId, int shardId) {
        return fileFinder.getShardDirPath(localDir, indexId, shardId);
    }

    @Override
    public Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId) {
        return fetch(fileFinder.getShardMetadataFilePath(localDir, snapshotId, indexId, shardId));
    }

    @Override
    public Path getBlobFilePath(String indexId, int shardId, String blobName) {
        return fetch(fileFinder.getBlobFilePath(localDir, indexId, shardId, blobName));
    }

    private Path fetch(Path path) {
        ensureFileExistsLocally(makeGcsUri(path), path);
        return path;
    }

    protected void ensureLocalDirectoryExists(Path localPath) {
        try {
            if (localPath != null) {
                Files.createDirectories(localPath);
            }
        } catch (IOException e) {
            throw new CantCreateLocalDir(localPath, e);
        }
    }

    protected boolean doesFileExistLocally(Path localPath) {
        return Files.exists(localPath);
    }

    private void ensureFileExistsLocally(GcsUri gcsUri, Path localPath) {
        ensureLocalDirectoryExists(localPath.getParent());

        if (doesFileExistLocally(localPath)) {
            log.atDebug().setMessage("File already exists locally: {}").addArgument(localPath).log();
            return;
        }

        log.atInfo()
            .setMessage("Downloading file from GCS: {} to {}").addArgument(gcsUri.uri).addArgument(localPath).log();

        Blob blob = storageClient.get(BlobId.of(gcsUri.bucketName, gcsUri.key));
        if (blob == null) {
            throw new CouldNotReadFromGcs(gcsUri.bucketName, gcsUri.key);
        }
        blob.downloadTo(localPath);
    }

    protected GcsUri makeGcsUri(Path filePath) {
        Path absLocalDir = localDir.toAbsolutePath().normalize();
        Path absFilePath = filePath.toAbsolutePath().normalize();

        String localDirStr = absLocalDir.toString();
        String filePathStr = absFilePath.toString();

        if (!filePathStr.startsWith(localDirStr)) {
            throw new IllegalArgumentException("File path must be under localDir: " + filePath);
        }

        String relativePathStr = filePathStr.substring(localDirStr.length());
        if (relativePathStr.startsWith(File.separator)) {
            relativePathStr = relativePathStr.substring(1);
        }

        relativePathStr = relativePathStr.replace('\\', '/');

        String baseUri = gcsRepoUri.uri.endsWith("/")
            ? gcsRepoUri.uri.substring(0, gcsRepoUri.uri.length() - 1)
            : gcsRepoUri.uri;

        String fullUri = relativePathStr.isEmpty()
            ? baseUri
            : baseUri + "/" + relativePathStr;

        return new GcsUri(fullUri);
    }

    protected List<String> listFilesInRoot() {
        String prefixKey = gcsRepoUri.key;
        if (prefixKey.endsWith("/")) {
            prefixKey = prefixKey.substring(0, prefixKey.length() - 1);
        }

        String listPrefix = prefixKey.isEmpty() ? null : prefixKey + "/";

        var options = new ArrayList<BlobListOption>();
        options.add(BlobListOption.delimiter("/"));
        if (listPrefix != null) {
            options.add(BlobListOption.prefix(listPrefix));
        }

        com.google.api.gax.paging.Page<Blob> blobs;
        try {
            blobs = storageClient.list(
                gcsRepoUri.bucketName,
                options.toArray(new BlobListOption[0])
            );
        } catch (StorageException e) {
            throw new CannotListObjects(gcsRepoUri.bucketName, prefixKey, e);
        }

        List<String> strippedKeys = new ArrayList<>();
        for (Blob blob : blobs.iterateAll()) {
            String key = blob.getName();
            String stripped = key.substring(listPrefix == null ? 0 : listPrefix.length());
            if (stripped.startsWith("/")) {
                stripped = stripped.substring(1);
            }
            if (!stripped.isEmpty() && !stripped.contains("/")) {
                strippedKeys.add(stripped);
            }
        }

        if (strippedKeys.isEmpty()) {
            throw new CannotFindSnapshotRepoRoot(gcsRepoUri.bucketName, prefixKey);
        }

        log.atDebug()
            .setMessage("From GcsRepo: top-level files under {} -> {}")
            .addArgument(gcsRepoUri)
            .addArgument(strippedKeys)
            .log();

        return strippedKeys;
    }

    public static class CannotFindSnapshotRepoRoot extends RfsException {
        public CannotFindSnapshotRepoRoot(String bucket, String prefix) {
            super("Cannot find the snapshot repository root in GCS bucket: " + bucket + ", prefix: " + prefix);
        }
    }

    public static class CantCreateLocalDir extends RfsException {
        public CantCreateLocalDir(Path localPath, Throwable cause) {
            super("Failed to create the GCS local download directory: " + localPath, cause);
        }
    }

    public static class CannotListObjects extends RfsException {
        public CannotListObjects(String bucket, String prefix, Throwable cause) {
            super("Failed to list objects in GCS bucket: " + bucket + ", prefix: " + prefix, cause);
        }
    }

    public static class CouldNotReadFromGcs extends RfsException implements SnapshotReadFailure {
        public CouldNotReadFromGcs(String bucket, String key) {
            super("Failed to read object from GCS bucket: " + bucket + ", key: " + key);
        }
    }
}
