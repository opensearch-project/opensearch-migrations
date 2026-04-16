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
        // Solr S3BackupRepository validates that the location directory exists in S3 before writing.
        // If the s3RepoUri has a subpath (e.g. s3://bucket/solr-migration-v3), we must ensure
        // the directory marker object exists at that path before calling the backup API.
        if (args.s3RepoUri != null && args.s3Region != null) {
            ensureS3LocationExists(args.s3RepoUri, args.s3Region, args.s3Endpoint);
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

    /**
     * Ensure the S3 directory marker exists for the Solr backup location.
     * Solr's S3BackupRepository checks that the location path exists (via HeadObject)
     * before accepting a backup request. For subpaths like s3://bucket/solr-migration-v3,
     * we must create a zero-byte directory marker object with the appropriate content-type.
     */
    private void ensureS3LocationExists(String s3RepoUri, String region, String endpoint) {
        var repoUri = new S3Uri(s3RepoUri);
        if (repoUri.key.isEmpty()) {
            log.info("S3 backup location is bucket root, no directory marker needed");
            return;
        }

        var dirKey = repoUri.key.endsWith("/") ? repoUri.key : repoUri.key + "/";
        log.info("Ensuring S3 directory marker exists: s3://{}/{}", repoUri.bucketName, dirKey);

        var s3ClientBuilder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isEmpty()) {
            s3ClientBuilder.endpointOverride(URI.create(endpoint));
            s3ClientBuilder.forcePathStyle(true);
            log.info("Using custom S3 endpoint: {}", endpoint);
        }
        try (var s3Client = s3ClientBuilder.build()) {
            // Check if the directory marker already exists
            try {
                s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(repoUri.bucketName)
                    .key(dirKey)
                    .build());
                log.info("S3 directory marker already exists");
                return;
            } catch (NoSuchKeyException e) {
                // Expected — need to create it
            }

            // Create the directory marker (matches Solr's S3StorageClient.createDirectory behavior)
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(repoUri.bucketName)
                    .key(dirKey)
                    .contentType("application/x-directory")
                    .build(),
                RequestBody.empty());
            log.info("Created S3 directory marker: s3://{}/{}", repoUri.bucketName, dirKey);
        } catch (Exception e) {
            log.warn("Failed to ensure S3 directory marker at s3://{}/{}: {} — continuing; "
                + "backup may still succeed if Solr's S3BackupRepository creates it implicitly.",
                repoUri.bucketName, dirKey, e.getMessage());
        }
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
}
