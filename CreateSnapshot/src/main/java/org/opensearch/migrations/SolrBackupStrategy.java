package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator;
import org.opensearch.migrations.bulkload.solr.SolrStandaloneBackupCreator;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Backup strategy for Solr sources. Handles both SolrCloud (Collections API)
 * and standalone (Core Admin / replication API) modes.
 * Uses {@link ConnectionContext} for authentication, sharing the same auth
 * path as the Elasticsearch side.
 */
@Slf4j
public class SolrBackupStrategy implements SourceBackupStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreateSnapshot.Args args;
    private final ConnectionContext connectionContext;
    private final SolrHttpClient httpClient;

    public SolrBackupStrategy(CreateSnapshot.Args args) {
        this.args = args;
        this.connectionContext = args.sourceArgs.toConnectionContext();
        this.httpClient = new SolrHttpClient(connectionContext);
    }

    @Override
    public void run() {
        var backupLocation = args.fileSystemRepoPath != null ? args.fileSystemRepoPath : args.s3RepoUri;
        var solrUrl = connectionContext.getUri().toString();

        if (args.solrCollections.isEmpty()) {
            try {
                args.solrCollections = discoverCollections(solrUrl, httpClient);
                log.info("Auto-discovered {} Solr collection(s): {}", args.solrCollections.size(), args.solrCollections);
            } catch (Exception e) {
                throw new ParameterException("Failed to discover Solr collections: " + e.getMessage());
            }
        }

        if (args.solrCollections.isEmpty()) {
            throw new ParameterException(
                "No Solr collections or cores found. Specify --solr-collections explicitly.");
        }

        if (isSolrCloud(solrUrl, httpClient)) {
            runCloudBackup(solrUrl, backupLocation);
        } else {
            runStandaloneBackup(solrUrl, backupLocation);
        }
    }

    // ---- Cloud vs standalone detection ----

    static boolean isSolrCloud(String solrUrl, SolrHttpClient httpClient) {
        try {
            var response = httpClient.getRaw(
                solrUrl + "/solr/admin/collections?action=LIST&wt=json", Duration.ofSeconds(5));
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("SolrCloud detection interrupted, assuming standalone");
            return false;
        } catch (Exception e) {
            log.info("SolrCloud detection failed, assuming standalone: {}", e.getMessage());
            return false;
        }
    }

    // ---- Collection / core discovery ----

    static List<String> discoverCollections(String solrUrl, SolrHttpClient httpClient) throws IOException {
        // Try SolrCloud Collections API first
        try {
            var json = httpClient.getString(
                solrUrl + "/solr/admin/collections?action=LIST&wt=json", Duration.ofSeconds(10));
            var node = MAPPER.readTree(json).path("collections");
            var collections = new ArrayList<String>();
            if (node.isArray()) {
                node.forEach(n -> collections.add(n.asText()));
            }
            if (!collections.isEmpty()) return collections;
        } catch (Exception e) {
            log.debug("Collections API failed, trying Core Admin: {}", e.getMessage());
        }
        // Fall back to Core Admin API (standalone)
        var json = httpClient.getString(
            solrUrl + "/solr/admin/cores?action=STATUS&wt=json", Duration.ofSeconds(10));
        return objectFieldKeys(MAPPER.readTree(json), "status");
    }

    private static List<String> objectFieldKeys(JsonNode root, String fieldName) {
        var result = new ArrayList<String>();
        collectObjectFieldKeys(root, fieldName, result);
        return result;
    }

    private static boolean collectObjectFieldKeys(JsonNode node, String fieldName, List<String> result) {
        if (node.has(fieldName) && node.get(fieldName).isObject()) {
            node.get(fieldName).fieldNames().forEachRemaining(result::add);
            return true;
        }
        for (JsonNode child : node) {
            if (child.isObject() && collectObjectFieldKeys(child, fieldName, result)) {
                return true;
            }
        }
        return false;
    }

    // ---- Backup execution ----

    private void runCloudBackup(String solrUrl, String backupLocation) {
        log.info("Detected SolrCloud — using Collections API backup");
        // Solr's BACKUP API validates that the `location` directory exists before writing
        // (both S3BackupRepository and LocalFileSystemRepository enforce this). Per-collection
        // backups use location=<base>/<snapshotName>, so we create that directory up-front.
        if (args.s3RepoUri != null && args.s3Region != null) {
            // S3: create markers at <prefix>/ (parent, when the URI has a subpath) and
            // <prefix>/<snapshotName>/ (the per-snapshot directory Solr checks via HeadObject).
            ensureS3LocationExists(args.s3RepoUri, args.snapshotName, args.s3Region, args.s3Endpoint);
        } else if (args.fileSystemRepoPath != null) {
            // Filesystem: create <fileSystemRepoPath>/<snapshotName>/ before the BACKUP call.
            // Solr runs inside its own container in real deployments, so this only works when
            // the backup target is a shared volume accessible from the CreateSnapshot process.
            ensureFileSystemLocationExists(args.fileSystemRepoPath, args.snapshotName);
        }
        var creator = new SolrSnapshotCreator(
            solrUrl, args.snapshotName, backupLocation,
            args.solrCollections, connectionContext, args.snapshotRepoName
        );
        creator.registerRepo();
        creator.createSnapshot();
        waitForCompletion(creator::isSnapshotFinished);
    }

    private void runStandaloneBackup(String solrUrl, String backupLocation) {
        log.info("Detected standalone Solr — using replication API backup");
        String repositoryName = null;
        if (args.s3RepoUri != null) {
            repositoryName = args.snapshotRepoName;
            var uri = URI.create(args.s3RepoUri);
            backupLocation = uri.getPath();
            if (backupLocation.startsWith("/")) {
                backupLocation = backupLocation.substring(1);
            }
            log.info("Using S3 backup repository '{}' with location prefix '{}'", repositoryName, backupLocation);
        }
        var creator = new SolrStandaloneBackupCreator(
            solrUrl, args.snapshotName, backupLocation,
            args.solrCollections, connectionContext, repositoryName
        );
        creator.createBackup();
        waitForCompletion(creator::isBackupFinished);
    }

    private void waitForCompletion(java.util.function.BooleanSupplier isFinished) {
        if (!args.noWait) {
            log.info("Waiting for Solr backup to complete...");
            while (!isFinished.getAsBoolean()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SolrSnapshotCreator.SolrBackupFailed("Interrupted while waiting for backup");
                }
            }
            log.info("Solr backup '{}' completed", args.snapshotName);
        } else {
            log.info("Solr backup '{}' initiated (no-wait mode)", args.snapshotName);
        }
    }

    /**
     * Ensure the S3 directory marker exists for the Solr backup location.
     * Solr's S3BackupRepository (SIP-12) checks that the location path exists
     * (via HeadObject on &lt;prefix&gt;/) before accepting a backup request.
     * For subpaths like s3://bucket/solr-migration-v3, we must create a zero-byte
     * directory marker object with content-type application/x-directory.
     *
     * <p>This uses the SDK S3 client directly (not Solr's built-in client) so we can
     * apply the custom endpoint (e.g. localstack) and path-style access when needed.
     */
    /* package */ void ensureS3LocationExists(String s3RepoUri, String snapshotName, String region, String endpoint) {
        var repoUri = new S3Uri(s3RepoUri);
        var parentKey = repoUri.key;
        var snapshotKey = (parentKey == null || parentKey.isEmpty())
            ? snapshotName + "/"
            : (parentKey.endsWith("/") ? parentKey : parentKey + "/") + snapshotName + "/";

        try (var s3Client = buildS3Client(region, endpoint)) {
            // Create the parent prefix marker only when the URI has a subpath. Bucket root
            // needs no marker (HeadObject on "" is a no-op).
            if (parentKey != null && !parentKey.isEmpty()) {
                var parentDirKey = parentKey.endsWith("/") ? parentKey : parentKey + "/";
                log.info("Ensuring S3 parent directory marker: s3://{}/{}", repoUri.bucketName, parentDirKey);
                createDirectoryMarkerIfMissing(s3Client, repoUri.bucketName, parentDirKey);
            }
            // Always create the per-snapshot directory marker — Solr 8's S3BackupRepository
            // HeadObject-checks this exact path when backup `location` includes the snapshot name.
            log.info("Ensuring S3 snapshot directory marker: s3://{}/{}", repoUri.bucketName, snapshotKey);
            createDirectoryMarkerIfMissing(s3Client, repoUri.bucketName, snapshotKey);
        } catch (Exception e) {
            log.warn("Failed to ensure S3 directory markers under s3://{}/{}: {} — continuing; "
                + "backup may still succeed if Solr's S3BackupRepository creates them implicitly.",
                repoUri.bucketName, snapshotKey, e.getMessage());
        }
    }

    /* package */ static S3Client buildS3Client(String region, String endpoint) {
        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isEmpty()) {
            var endpointUri = endpoint.contains("://") ? endpoint : "http://" + endpoint;
            builder.endpointOverride(URI.create(endpointUri));
            builder.forcePathStyle(true);
            log.info("Using custom S3 endpoint: {} (path-style)", endpointUri);
        }
        return builder.build();
    }

    /* package */ void createDirectoryMarkerIfMissing(S3Client s3Client, String bucket, String dirKey) {
        if (s3DirectoryMarkerExists(s3Client, bucket, dirKey)) {
            log.info("S3 directory marker already exists");
            return;
        }
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(dirKey)
                .contentType("application/x-directory")
                .build(),
            RequestBody.empty());
        log.info("Created S3 directory marker: s3://{}/{}", bucket, dirKey);
    }

    /* package */ boolean s3DirectoryMarkerExists(S3Client s3Client, String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Ensure the filesystem backup location directory exists before invoking Solr's BACKUP
     * API. Solr validates that the {@code location} path resolves to an existing directory
     * (LocalFileSystemRepository enforces this) and will return HTTP 500 with
     * "specified location ... does not exist" otherwise. Creates parents as needed; this is
     * a no-op if the directory already exists.
     *
     * <p>Note: this only helps when the CreateSnapshot process shares a filesystem with the
     * Solr node. In containerized/remote setups the customer must ensure the path exists
     * inside the Solr container.
     */
    /* package */ void ensureFileSystemLocationExists(String fileSystemRepoPath, String snapshotName) {
        try {
            var snapshotDir = java.nio.file.Paths.get(fileSystemRepoPath, snapshotName);
            log.info("Ensuring filesystem backup directory: {}", snapshotDir);
            java.nio.file.Files.createDirectories(snapshotDir);
        } catch (Exception e) {
            // Most likely cause in production: Solr runs in a separate container/host and the
            // CreateSnapshot process can't reach its filesystem. That's expected — continue and
            // let Solr return its own error if the directory still doesn't exist from its view.
            log.warn("Failed to create filesystem backup directory {}/{}: {} — continuing; "
                + "backup will fail if Solr cannot see the directory.",
                fileSystemRepoPath, snapshotName, e.getMessage());
        }
    }
}
