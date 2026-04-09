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
 * Creates SolrCloud collection backups via the Collections API.
 * Supports optional Basic Auth.
 */
@Slf4j
public class SolrSnapshotCreator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATUS_FIELD = "status";

    private final String solrBaseUrl;
    private final HttpClient httpClient;
    @Getter
    private final String backupName;
    private final String backupLocation;
    private final List<String> collections;
    private final String authHeader;
    private final String repositoryName;

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> collections
    ) {
        this(solrBaseUrl, backupName, backupLocation, collections, null, null, null);
    }

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> collections,
        String username,
        String password
    ) {
        this(solrBaseUrl, backupName, backupLocation, collections, username, password, null);
    }

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> collections,
        String username,
        String password,
        String repositoryName
    ) {
        this.solrBaseUrl = solrBaseUrl.endsWith("/")
            ? solrBaseUrl.substring(0, solrBaseUrl.length() - 1) : solrBaseUrl;
        this.backupName = backupName;
        this.backupLocation = backupLocation;
        this.collections = collections;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        if (username != null && password != null) {
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        } else {
            this.authHeader = null;
        }
        this.repositoryName = repositoryName;
    }

    /** No-op for SolrCloud — backup location is specified per-request. */
    public void registerRepo() {
        log.info("SolrCloud does not require repo registration; backup location: {}", backupLocation);
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
                // S3 backups use the configured repository; location is a path within the bucket
                urlBuilder.append("&repository=").append(repositoryName).append("&location=/");
            } else if (backupLocation != null) {
                urlBuilder.append("&location=").append(backupLocation);
            }
            var url = urlBuilder.toString();
            log.info("Initiating SolrCloud backup for collection '{}': async={}", collection, asyncId);
            var response = getJson(url);
            var status = response.path("responseHeader").path(STATUS_FIELD).asInt(-1);
            if (status != 0) {
                var errorMsg = response.path("error").path("msg").asText(response.toString());
                throw new SolrBackupFailed("Backup initiation failed for collection '" + collection + "': " + errorMsg);
            }
            log.info("SolrCloud backup initiated for collection '{}'", collection);
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
            var response = getJson(url);
            var state = response.path(STATUS_FIELD).path("state").asText("");
            if ("completed".equalsIgnoreCase(state)) {
                log.info("SolrCloud backup completed for collection '{}'", collection);
                continue;
            }
            if ("running".equalsIgnoreCase(state) || "submitted".equalsIgnoreCase(state)) {
                log.info("SolrCloud backup still in progress for collection '{}': {}", collection, state);
                return false;
            }
            if ("failed".equalsIgnoreCase(state) || "notfound".equalsIgnoreCase(state)) {
                var msg = response.path(STATUS_FIELD).path("msg").asText(state);
                throw new SolrBackupFailed("Backup failed for collection '" + collection + "': " + msg);
            }
            log.warn("Unknown backup state '{}' for collection '{}', treating as in-progress", state, collection);
            return false;
        }
        return true;
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
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new SolrBackupFailed("Solr authentication failed (HTTP " + response.statusCode() + ") for " + url);
            }
            if (response.statusCode() != 200) {
                throw new SolrBackupFailed("Solr returned HTTP " + response.statusCode() + " for " + url);
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new SolrBackupFailed("Failed to communicate with Solr: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrBackupFailed("Interrupted while communicating with Solr");
        }
    }

    public static class SolrBackupFailed extends RuntimeException {
        public SolrBackupFailed(String message) {
            super(message);
        }
    }
}
