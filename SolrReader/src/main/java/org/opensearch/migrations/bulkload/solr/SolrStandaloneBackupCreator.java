package org.opensearch.migrations.bulkload.solr;

import java.util.List;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates backups for standalone (non-SolrCloud) Solr instances using the
 * replication handler API: {@code /replication?command=backup}.
 *
 * <p>Unlike {@link SolrSnapshotCreator} which uses the SolrCloud Collections API,
 * this works with single-node Solr deployments where each core is backed up individually.
 * Uses {@link ConnectionContext} for authentication (Basic Auth, SigV4, etc.).
 */
@Slf4j
public class SolrStandaloneBackupCreator {

    private final String solrBaseUrl;
    private final SolrHttpClient httpClient;
    @Getter
    private final String backupName;
    private final String backupLocation;
    private final String repositoryName;
    private final List<String> cores;

    public SolrStandaloneBackupCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores,
        ConnectionContext connectionContext
    ) {
        this(solrBaseUrl, backupName, backupLocation, cores, connectionContext, null);
    }

    public SolrStandaloneBackupCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores,
        ConnectionContext connectionContext,
        String repositoryName
    ) {
        this.solrBaseUrl = solrBaseUrl.endsWith("/")
            ? solrBaseUrl.substring(0, solrBaseUrl.length() - 1) : solrBaseUrl;
        this.backupName = backupName;
        this.backupLocation = backupLocation;
        this.repositoryName = repositoryName;
        this.cores = cores;
        this.httpClient = new SolrHttpClient(connectionContext);
    }

    /** Trigger a backup for each core via the replication handler. */
    public void createBackup() {
        for (var core : cores) {
            var url = String.format(
                "%s/solr/%s/replication?command=backup&location=%s&name=%s&wt=json",
                solrBaseUrl, core, backupLocation, backupName
            );
            if (repositoryName != null) {
                url += "&repository=" + repositoryName;
            }
            log.atInfo().setMessage("Initiating standalone backup for core '{}' to {} (repository={})")
                .addArgument(core).addArgument(backupLocation).addArgument(repositoryName != null ? repositoryName : "local").log();
            var response = httpClient.getJson(url);
            var status = response.path("status").asText("");
            if (!"OK".equalsIgnoreCase(status)) {
                var errorMsg = response.path("message").asText(response.toString());
                throw new SolrSnapshotCreator.SolrBackupFailed(
                    "Backup failed for core '" + core + "': " + errorMsg);
            }
            log.atInfo().setMessage("Standalone backup initiated for core '{}'").addArgument(core).log();
        }
    }

    /** Check if all core backups have completed by querying replication details. */
    public boolean isBackupFinished() {
        for (var core : cores) {
            var url = String.format(
                "%s/solr/%s/replication?command=details&wt=json",
                solrBaseUrl, core
            );
            var response = httpClient.getJson(url);
            var backup = response.path("details").path("backup");
            if (backup.isMissingNode()) {
                log.atInfo().setMessage("No backup info yet for core '{}'").addArgument(core).log();
                return false;
            }

            var status = extractNamedListValue(backup, "status");
            if ("success".equalsIgnoreCase(status)) {
                log.atInfo().setMessage("Backup completed for core '{}'").addArgument(core).log();
                continue;
            }
            if (status == null || "In Progress".equalsIgnoreCase(status)) {
                log.atInfo().setMessage("Backup still in progress for core '{}'").addArgument(core).log();
                return false;
            }
            if ("failed".equalsIgnoreCase(status) || "exception".equalsIgnoreCase(status)) {
                var msg = extractNamedListValue(backup, "exception");
                throw new SolrSnapshotCreator.SolrBackupFailed(
                    "Backup failed for core '" + core + "': " + (msg != null ? msg : status));
            }
            log.atWarn().setMessage("Unknown backup status '{}' for core '{}', treating as in-progress").addArgument(status).addArgument(core).log();
            return false;
        }
        return true;
    }

    /** Extract a value from a Solr NamedList JSON array: ["key1","val1","key2","val2",...] */
    private static String extractNamedListValue(JsonNode namedList, String key) {
        if (namedList == null || !namedList.isArray()) {
            return null;
        }
        for (int i = 0; i < namedList.size() - 1; i++) {
            if (key.equals(namedList.get(i).asText())) {
                return namedList.get(i + 1).asText();
            }
        }
        return null;
    }
}
