package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates backups for standalone (non-SolrCloud) Solr instances using the
 * replication handler API: {@code /replication?command=backup}.
 *
 * <p>Unlike {@link SolrSnapshotCreator} which uses the SolrCloud Collections API,
 * this works with single-node Solr deployments where each core is backed up individually.
 */
@Slf4j
public class SolrStandaloneBackupCreator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String solrBaseUrl;
    private final HttpClient httpClient;
    @Getter
    private final String backupName;
    private final String backupLocation;
    private final String repositoryName;
    private final List<String> cores;
    private final String authHeader;

    public SolrStandaloneBackupCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores
    ) {
        this(solrBaseUrl, backupName, backupLocation, cores, null, null, null);
    }

    public SolrStandaloneBackupCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores,
        String username,
        String password
    ) {
        this(solrBaseUrl, backupName, backupLocation, cores, username, password, null);
    }

    public SolrStandaloneBackupCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores,
        String username,
        String password,
        String repositoryName
    ) {
        this.solrBaseUrl = solrBaseUrl.endsWith("/")
            ? solrBaseUrl.substring(0, solrBaseUrl.length() - 1) : solrBaseUrl;
        this.backupName = backupName;
        this.backupLocation = backupLocation;
        this.repositoryName = repositoryName;
        this.cores = cores;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.authHeader = (username != null && password != null)
            ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
            : null;
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
            log.info("Initiating standalone backup for core '{}' to {} (repository={})",
                core, backupLocation, repositoryName != null ? repositoryName : "local");
            var response = getJson(url);
            var status = response.path("status").asText("");
            if (!"OK".equalsIgnoreCase(status)) {
                var errorMsg = response.path("message").asText(response.toString());
                throw new SolrSnapshotCreator.SolrBackupFailed(
                    "Backup failed for core '" + core + "': " + errorMsg);
            }
            log.info("Standalone backup initiated for core '{}'", core);
        }
    }

    /** Check if all core backups have completed by querying replication details. */
    public boolean isBackupFinished() {
        for (var core : cores) {
            var url = String.format(
                "%s/solr/%s/replication?command=details&wt=json",
                solrBaseUrl, core
            );
            var response = getJson(url);
            var backup = response.path("details").path("backup");
            if (backup.isMissingNode()) {
                log.info("No backup info yet for core '{}'", core);
                return false;
            }

            // Solr returns backup as a NamedList (JSON array of alternating key/value pairs)
            // e.g. ["startTime","...","status","success","snapshotName","test_bak"]
            var status = extractNamedListValue(backup, "status");
            if ("success".equalsIgnoreCase(status)) {
                log.info("Backup completed for core '{}'", core);
                continue;
            }
            if (status == null || "In Progress".equalsIgnoreCase(status)) {
                log.info("Backup still in progress for core '{}'", core);
                return false;
            }
            if ("failed".equalsIgnoreCase(status) || "exception".equalsIgnoreCase(status)) {
                var msg = extractNamedListValue(backup, "exception");
                throw new SolrSnapshotCreator.SolrBackupFailed(
                    "Backup failed for core '" + core + "': " + (msg != null ? msg : status));
            }
            log.warn("Unknown backup status '{}' for core '{}', treating as in-progress", status, core);
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

    private JsonNode getJson(String url) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        var request = builder.build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SolrSnapshotCreator.SolrBackupFailed(
                    "Solr returned HTTP " + response.statusCode() + " for " + url
                    + " — response body: " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new SolrSnapshotCreator.SolrBackupFailed(
                "Failed to communicate with Solr: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrSnapshotCreator.SolrBackupFailed("Interrupted while communicating with Solr");
        }
    }
}
