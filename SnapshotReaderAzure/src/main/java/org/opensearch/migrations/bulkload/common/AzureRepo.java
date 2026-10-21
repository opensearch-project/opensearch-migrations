package org.opensearch.migrations.bulkload.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.azure.core.credential.AzureSasCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.common.StorageSharedKeyCredential;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AzureRepo implements SourceRepo {

    static final String ACCOUNT_KEY_ENV = "AZURE_STORAGE_ACCOUNT_KEY";
    static final String SAS_TOKEN_ENV = "AZURE_STORAGE_SAS_TOKEN";

    private final Path localDir;
    private final BlobContainerClient containerClient;
    private final SnapshotFileFinder fileFinder;

    @Getter
    private final AzureUri azureRepoUri;

    @Override
    public String toString() {
        return String.format("AzureRepo [uri=%s]", azureRepoUri.uri);
    }

    public static AzureRepo create(Path localDir, AzureUri azureUri, SnapshotFileFinder finder) {
        return create(localDir, azureUri, null, finder);
    }

    public static AzureRepo create(Path localDir, AzureUri azureUri, String endpoint, SnapshotFileFinder finder) {
        var builder = new BlobServiceClientBuilder();

        // Azurite and other emulators use path-style addressing, with the storage account as the
        // first path segment (http://host:10000/devstoreaccount1). Real Azure Blob Storage uses
        // host-style addressing (https://<account>.blob.core.windows.net).
        String resolvedEndpoint = (endpoint != null && !endpoint.isEmpty())
            ? stripTrailingSlash(endpoint) + "/" + azureUri.accountName
            : "https://" + azureUri.accountName + ".blob.core.windows.net";
        builder.endpoint(resolvedEndpoint);
        log.atInfo().setMessage("Using Azure Blob endpoint: {}").addArgument(resolvedEndpoint).log();

        String accountKey = System.getenv(ACCOUNT_KEY_ENV);
        String sasToken = System.getenv(SAS_TOKEN_ENV);
        if (accountKey != null && !accountKey.isEmpty()) {
            builder.credential(new StorageSharedKeyCredential(azureUri.accountName, accountKey));
        } else if (sasToken != null && !sasToken.isEmpty()) {
            builder.credential(new AzureSasCredential(sasToken));
        } else {
            // No explicit secret supplied, so fall back to the ambient identity chain (managed
            // identity on AKS, Azure CLI login locally, workload identity federation).
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }

        BlobContainerClient container = builder.buildClient().getBlobContainerClient(azureUri.containerName);
        return new AzureRepo(localDir, azureUri, container, finder);
    }

    protected AzureRepo(
        Path localDir,
        AzureUri azureUri,
        BlobContainerClient containerClient,
        SnapshotFileFinder fileFinder
    ) {
        this.localDir = localDir;
        this.azureRepoUri = azureUri;
        this.containerClient = containerClient;
        this.fileFinder = fileFinder;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
            throw new CannotFindSnapshotRepoRoot(azureRepoUri.containerName, azureRepoUri.key);
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
        ensureFileExistsLocally(makeAzureUri(path), path);
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

    private void ensureFileExistsLocally(AzureUri azureUri, Path localPath) {
        ensureLocalDirectoryExists(localPath.getParent());

        if (doesFileExistLocally(localPath)) {
            log.atDebug().setMessage("File already exists locally: {}").addArgument(localPath).log();
            return;
        }

        log.atInfo()
            .setMessage("Downloading file from Azure Blob Storage: {} to {}")
            .addArgument(azureUri.uri)
            .addArgument(localPath)
            .log();

        try {
            containerClient.getBlobClient(azureUri.key).downloadToFile(localPath.toString(), true);
        } catch (BlobStorageException e) {
            // A failed download (missing blob, access denied, or a service error that exhausted
            // the client's retries) is a non-retriable snapshot read failure. Classify it so the
            // worker surfaces the snapshot path + cause and exits with the dedicated code instead
            // of dying with a generic, unlabeled error. Local filesystem errors are not
            // BlobStorageException and keep their own classification.
            throw new CouldNotReadFromAzure(azureUri.containerName, azureUri.key, e);
        }
    }

    protected AzureUri makeAzureUri(Path filePath) {
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

        String baseUri = stripTrailingSlash(azureRepoUri.uri);

        String fullUri = relativePathStr.isEmpty()
            ? baseUri
            : baseUri + "/" + relativePathStr;

        return new AzureUri(fullUri);
    }

    protected List<String> listFilesInRoot() {
        String prefixKey = stripTrailingSlash(azureRepoUri.key);
        String listPrefix = prefixKey.isEmpty() ? null : prefixKey + "/";

        var options = new ListBlobsOptions();
        if (listPrefix != null) {
            options.setPrefix(listPrefix);
        }

        List<String> strippedKeys = new ArrayList<>();
        try {
            for (BlobItem item : containerClient.listBlobsByHierarchy("/", options, (Duration) null)) {
                // BlobItem.isPrefix() returns a boxed java.lang.Boolean and is null for real blobs,
                // so compare rather than unbox. Prefixes are the "subdirectories" of the root and
                // are not top-level files.
                if (Boolean.TRUE.equals(item.isPrefix())) {
                    continue;
                }
                String key = item.getName();
                String stripped = key.substring(listPrefix == null ? 0 : listPrefix.length());
                if (stripped.startsWith("/")) {
                    stripped = stripped.substring(1);
                }
                if (!stripped.isEmpty() && !stripped.contains("/")) {
                    strippedKeys.add(stripped);
                }
            }
        } catch (BlobStorageException e) {
            throw new CannotListBlobs(azureRepoUri.containerName, prefixKey, e);
        }

        // Listing a prefix that matches nothing returns zero items and throws nothing, so this
        // explicit check is the only thing that surfaces a missing or misspelled repo root. A
        // missing container, by contrast, throws BlobStorageException above.
        if (strippedKeys.isEmpty()) {
            throw new CannotFindSnapshotRepoRoot(azureRepoUri.containerName, prefixKey);
        }

        log.atDebug()
            .setMessage("From AzureRepo: top-level files under {} -> {}")
            .addArgument(azureRepoUri)
            .addArgument(strippedKeys)
            .log();

        return strippedKeys;
    }

    public static class CannotFindSnapshotRepoRoot extends RfsException implements SnapshotReadFailure {
        public CannotFindSnapshotRepoRoot(String container, String prefix) {
            super("Cannot find the snapshot repository root in Azure container: " + container
                + ", prefix: " + prefix);
        }
    }

    // Intentionally NOT a SnapshotReadFailure: this is a local filesystem problem, not a problem
    // reading the snapshot.
    public static class CantCreateLocalDir extends RfsException {
        public CantCreateLocalDir(Path localPath, Throwable cause) {
            super("Failed to create the Azure local download directory: " + localPath, cause);
        }
    }

    public static class CannotListBlobs extends RfsException implements SnapshotReadFailure {
        public CannotListBlobs(String container, String prefix, Throwable cause) {
            super("Failed to list blobs in Azure container: " + container + ", prefix: " + prefix, cause);
        }
    }

    public static class CouldNotReadFromAzure extends RfsException implements SnapshotReadFailure {
        public CouldNotReadFromAzure(String container, String key, Throwable cause) {
            super("Failed to read blob from Azure container: " + container + ", key: " + key, cause);
        }
    }
}
