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

import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.failed;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.failureMessage;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.partial;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.passed;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.skipped;

@Slf4j
public class AzureRepo implements SourceRepo {
    private static final String READ_OBJECT_STAGE = "read-object";
    private static final String READ_OBJECT_LABEL = "Read repository object";

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
        return create(localDir, azureUri, null, null, null, finder);
    }

    public static AzureRepo create(
        Path localDir,
        AzureUri azureUri,
        String endpoint,
        String accountKey,
        String sasToken,
        SnapshotFileFinder finder
    ) {
        boolean hasAccountKey = accountKey != null && !accountKey.isEmpty();
        boolean hasSasToken = sasToken != null && !sasToken.isEmpty();
        if (hasAccountKey && hasSasToken) {
            throw new IllegalArgumentException("Supply either an Azure storage account key or a SAS token, not both");
        }

        var builder = new BlobServiceClientBuilder();

        // Emulators address the account by path; Azure uses https://<account>.blob.core.windows.net.
        String resolvedEndpoint = (endpoint != null && !endpoint.isEmpty())
            ? stripTrailingSlash(endpoint) + "/" + azureUri.accountName
            : "https://" + azureUri.accountName + ".blob.core.windows.net";
        builder.endpoint(resolvedEndpoint);
        log.atInfo().setMessage("Using Azure Blob endpoint: {}").addArgument(resolvedEndpoint).log();

        if (hasAccountKey) {
            builder.credential(new StorageSharedKeyCredential(azureUri.accountName, accountKey));
        } else if (hasSasToken) {
            builder.credential(new AzureSasCredential(sasToken));
        } else {
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
            // Local filesystem errors are not BlobStorageException, so they keep their own classification.
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
                // isPrefix() returns a boxed Boolean that is null for real blobs.
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

        // An empty listing throws nothing, so a missing repo root is only detected here.
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

    /**
     * Checks the same Azure credential and endpoint path used by snapshot readers without requiring
     * that a snapshot already exists. Object reads are restricted to one byte so this check cannot
     * accidentally download a large shard file.
     */
    public RepositoryAccessCheckResult checkAccess() {
        var stages = new ArrayList<RepositoryAccessCheckResult.Stage>();
        String prefix = normalizedRepoPrefix();
        Iterable<BlobItem> blobs;
        try {
            var options = new ListBlobsOptions().setMaxResultsPerPage(25);
            if (!prefix.isEmpty()) {
                options.setPrefix(prefix);
            }
            blobs = containerClient.listBlobs(options, null);
            stages.add(passed(
                "list-prefix",
                "List repository prefix",
                "The configured repository prefix is accessible."
            ));
        } catch (RuntimeException e) {
            stages.add(failed("list-prefix", "List repository prefix", failureMessage(e)));
            stages.add(skipped(
                READ_OBJECT_STAGE,
                READ_OBJECT_LABEL,
                "Object read was not attempted because the repository prefix could not be listed."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.FAILED,
                "azure",
                azureRepoUri.uri,
                "Unable to list the configured Azure repository prefix.",
                List.copyOf(stages)
            );
        }

        BlobItem readableObject = null;
        for (BlobItem blob : blobs) {
            if (blob.getName() != null && !blob.getName().isBlank() && !blob.getName().endsWith("/")) {
                readableObject = blob;
                break;
            }
        }
        if (readableObject == null) {
            stages.add(partial(
                READ_OBJECT_STAGE,
                READ_OBJECT_LABEL,
                "No object is currently available under the configured prefix to verify read access."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.PARTIALLY_VERIFIED,
                "azure",
                azureRepoUri.uri,
                "The Azure repository prefix is accessible, but object read access could not yet be verified.",
                List.copyOf(stages)
            );
        }

        try (var stream = containerClient.getBlobClient(readableObject.getName()).openInputStream()) {
            stream.read();
            stages.add(passed(
                READ_OBJECT_STAGE,
                READ_OBJECT_LABEL,
                "An object under the configured repository prefix is readable."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.VALID,
                "azure",
                azureRepoUri.uri,
                "The Azure repository prefix can be listed and its objects can be read.",
                List.copyOf(stages)
            );
        } catch (RuntimeException | IOException e) {
            stages.add(failed(READ_OBJECT_STAGE, READ_OBJECT_LABEL, failureMessage(e)));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.FAILED,
                "azure",
                azureRepoUri.uri,
                "The Azure repository prefix is accessible, but an object could not be read.",
                List.copyOf(stages)
            );
        }
    }

    private String normalizedRepoPrefix() {
        if (azureRepoUri.key.isEmpty()) {
            return "";
        }
        return azureRepoUri.key.endsWith("/") ? azureRepoUri.key : azureRepoUri.key + "/";
    }

    public static class CannotFindSnapshotRepoRoot extends RfsException implements SnapshotReadFailure {
        public CannotFindSnapshotRepoRoot(String container, String prefix) {
            super("Cannot find the snapshot repository root in Azure container: " + container
                + ", prefix: " + prefix);
        }
    }

    // Not a SnapshotReadFailure: this is a local filesystem problem.
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
