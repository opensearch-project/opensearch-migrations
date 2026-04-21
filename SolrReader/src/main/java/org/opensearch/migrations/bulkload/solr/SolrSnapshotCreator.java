package org.opensearch.migrations.bulkload.solr;

import java.net.URI;
import java.util.List;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates SolrCloud collection backups via the Collections API.
 * Uses {@link ConnectionContext} for authentication (Basic Auth, SigV4, etc.).
 */
@Slf4j
public class SolrSnapshotCreator {

    private static final String STATUS_FIELD = "status";

    private final String solrBaseUrl;
    private final SolrHttpClient httpClient;
    @Getter
    private final String backupName;
    private final String backupLocation;
    private final List<String> collections;
    private final String repositoryName;

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> collections,
        ConnectionContext connectionContext
    ) {
        this(solrBaseUrl, backupName, backupLocation, collections, connectionContext, null);
    }

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> collections,
        ConnectionContext connectionContext,
        String repositoryName
    ) {
        this.solrBaseUrl = solrBaseUrl.endsWith("/")
            ? solrBaseUrl.substring(0, solrBaseUrl.length() - 1) : solrBaseUrl;
        this.backupName = backupName;
        this.backupLocation = backupLocation;
        this.collections = collections;
        this.httpClient = new SolrHttpClient(connectionContext);
        this.repositoryName = repositoryName;
    }

    /** No-op for SolrCloud — backup location is specified per-request. */
    public void registerRepo() {
        log.atInfo().setMessage("SolrCloud does not require repo registration; backup location: {}").addArgument(backupLocation).log();
    }

    /** Trigger an async backup for each collection via the Collections API. */
    public void createSnapshot() {
        for (var collection : collections) {
            // Solr 8's incremental backup (default since 8.9) enforces one collection per
            // backup name. To keep all collections under a single snapshot directory that the
            // downstream reader expects (<baseLocation>/<snapshotName>/<collection>/...), we
            // set Solr's `location` to <baseLocation>/<snapshotName> and `name` to <collection>
            // so each call writes to a distinct subdirectory under the shared snapshot folder.
            var asyncId = asyncIdFor(collection);
            var urlBuilder = new StringBuilder(String.format(
                "%s/solr/admin/collections?action=BACKUP&name=%s&collection=%s&async=%s&wt=json",
                solrBaseUrl, collection, collection, asyncId
            ));
            var perCollectionLocation = buildPerCollectionLocation(backupLocation, backupName);
            if (backupLocation != null && backupLocation.startsWith("s3://") && repositoryName != null) {
                // For S3 backups, Solr's S3BackupRepository writes under the configured bucket
                // (in solr.xml) using `location` as the prefix path. Extract the path portion
                // from s3://<bucket>/<path>/<snapshotName> — e.g. /foo/<snapshotName> — so
                // Solr writes to <bucket>/<path>/<snapshotName>/<collection>/.
                urlBuilder.append("&repository=").append(repositoryName)
                    .append("&location=").append(perCollectionLocation);
            } else if (backupLocation != null) {
                urlBuilder.append("&location=").append(perCollectionLocation);
            }
            var url = urlBuilder.toString();
            log.atInfo().setMessage("Initiating SolrCloud backup for collection '{}': name={} async={} location={}")
                .addArgument(collection).addArgument(collection).addArgument(asyncId).addArgument(perCollectionLocation).log();
            var response = httpClient.getJson(url);
            var status = response.path("responseHeader").path(STATUS_FIELD).asInt(-1);
            if (status != 0) {
                var errorMsg = response.path("error").path("msg").asText(response.toString());
                throw new SolrBackupFailed("Backup initiation failed for collection '" + collection + "': " + errorMsg);
            }
            log.atInfo().setMessage("SolrCloud backup initiated for collection '{}'").addArgument(collection).log();
        }
    }

    /**
     * Build the Solr backup `location` parameter for a specific collection.
     *
     * <p>For S3 locations, strips the scheme/bucket and appends the snapshot name so that
     * Solr writes collection subdirectories directly under <snapshotName>/ (matching the
     * reader's expectation). For filesystem locations, appends the snapshot name to the
     * configured path. Returns {@code null} when {@code location} is null.
     */
    static String buildPerCollectionLocation(String location, String snapshotName) {
        if (location == null) {
            return null;
        }
        if (location.startsWith("s3://")) {
            var base = extractS3Path(location);
            if ("/".equals(base)) {
                return "/" + snapshotName;
            }
            return base + "/" + snapshotName;
        }
        // Filesystem: append snapshotName, preserving a trailing slash.
        if (location.endsWith("/")) {
            return location + snapshotName;
        }
        return location + "/" + snapshotName;
    }

    /** Per-collection async id used for REQUESTSTATUS polling. Exposed for tests. */
    public static String asyncIdFor(String snapshotName, String collection) {
        return snapshotName + "-" + collection;
    }

    private String asyncIdFor(String collection) {
        return asyncIdFor(backupName, collection);
    }

    /** Check if all collection backups have completed by polling async request status. */
    public boolean isSnapshotFinished() {
        for (var collection : collections) {
            var asyncId = asyncIdFor(collection);
            var url = String.format(
                "%s/solr/admin/collections?action=REQUESTSTATUS&requestid=%s&wt=json",
                solrBaseUrl, asyncId
            );
            var response = httpClient.getJson(url);
            var state = response.path(STATUS_FIELD).path("state").asText("");
            if ("completed".equalsIgnoreCase(state)) {
                log.atInfo().setMessage("SolrCloud backup completed for collection '{}'").addArgument(collection).log();
                continue;
            }
            if ("running".equalsIgnoreCase(state) || "submitted".equalsIgnoreCase(state)) {
                log.atInfo().setMessage("SolrCloud backup still in progress for collection '{}': {}").addArgument(collection).addArgument(state).log();
                return false;
            }
            if ("failed".equalsIgnoreCase(state) || "notfound".equalsIgnoreCase(state)) {
                var msg = response.path(STATUS_FIELD).path("msg").asText(state);
                throw new SolrBackupFailed("Backup failed for collection '" + collection + "': " + msg);
            }
            log.atWarn().setMessage("Unknown backup state '{}' for collection '{}', treating as in-progress").addArgument(state).addArgument(collection).log();
            return false;
        }
        return true;
    }

    public static class SolrBackupFailed extends RuntimeException {
        public SolrBackupFailed(String message) {
            super(message);
        }
    }

    /**
     * Extract the path portion of an S3 URI for use as Solr's `location` parameter.
     * Returns "/" for bucket-root URIs, or a leading-slash path like "/foo/bar" when a
     * subpath is present. Solr's BACKUP API requires a leading slash on the location.
     */
    static String extractS3Path(String s3Uri) {
        try {
            var path = URI.create(s3Uri).getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return "/";
            }
            // Trim trailing slash so Solr doesn't produce double-slashes when appending backup name.
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path.startsWith("/") ? path : "/" + path;
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse S3 URI '{}', falling back to location=/: {}", s3Uri, e.getMessage());
            return "/";
        }
    }

}
