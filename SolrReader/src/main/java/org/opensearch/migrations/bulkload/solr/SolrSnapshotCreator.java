package org.opensearch.migrations.bulkload.solr;

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
            var asyncId = backupName + "-" + collection;
            var urlBuilder = new StringBuilder(String.format(
                "%s/solr/admin/collections?action=BACKUP&name=%s&collection=%s&async=%s&wt=json",
                solrBaseUrl, backupName, collection, asyncId
            ));
            if (backupLocation != null && backupLocation.startsWith("s3://") && repositoryName != null) {
                // Solr S3BackupRepository validates the location exists in S3 before writing.
                // The bucket is configured in solr.xml; backups always go to the bucket root.
                urlBuilder.append("&repository=").append(repositoryName).append("&location=/");
            } else if (backupLocation != null) {
                urlBuilder.append("&location=").append(backupLocation);
            }
            var url = urlBuilder.toString();
            log.atInfo().setMessage("Initiating SolrCloud backup for collection '{}': async={}").addArgument(collection).addArgument(asyncId).log();
            var response = httpClient.getJson(url);
            var status = response.path("responseHeader").path(STATUS_FIELD).asInt(-1);
            if (status != 0) {
                var errorMsg = response.path("error").path("msg").asText(response.toString());
                throw new SolrBackupFailed("Backup initiation failed for collection '" + collection + "': " + errorMsg);
            }
            log.atInfo().setMessage("SolrCloud backup initiated for collection '{}'").addArgument(collection).log();
        }
    }

    /** Check if all collection backups have completed by polling async request status. */
    public boolean isSnapshotFinished() {
        for (var collection : collections) {
            var asyncId = backupName + "-" + collection;
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

}
