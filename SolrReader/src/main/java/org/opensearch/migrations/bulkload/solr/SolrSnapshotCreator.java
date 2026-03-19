package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates a Solr replication backup, which produces Lucene segment files
 * that can be read by {@link SolrBackupSource}.
 *
 * <p>For each core, calls the Solr replication API:
 * {@code /solr/<core>/replication?command=backup&location=<path>&name=<name>}
 */
@Slf4j
public class SolrSnapshotCreator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String solrBaseUrl;
    private final HttpClient httpClient;
    @Getter
    private final String backupName;
    private final String backupLocation;
    private final List<String> cores;

    public SolrSnapshotCreator(
        String solrBaseUrl,
        String backupName,
        String backupLocation,
        List<String> cores
    ) {
        this.solrBaseUrl = solrBaseUrl.endsWith("/")
            ? solrBaseUrl.substring(0, solrBaseUrl.length() - 1) : solrBaseUrl;
        this.backupName = backupName;
        this.backupLocation = backupLocation;
        this.cores = cores;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** No-op for Solr — backup location is specified per-request. */
    public void registerRepo() {
        log.info("Solr does not require repo registration; backup location: {}", backupLocation);
    }

    /** Trigger a replication backup for each core. */
    public void createSnapshot() {
        for (var core : cores) {
            var url = String.format(
                "%s/solr/%s/replication?command=backup&location=%s&name=%s",
                solrBaseUrl, core, backupLocation, backupName
            );
            log.info("Initiating Solr backup for core '{}': {}", core, url);
            var response = getJson(url);
            var status = response.path("status").asText("UNKNOWN");
            if (!"OK".equalsIgnoreCase(status)) {
                throw new SolrBackupFailed("Backup initiation failed for core '" + core + "': " + response);
            }
            log.info("Solr backup initiated for core '{}'", core);
        }
    }

    /** Check if all core backups have completed. */
    public boolean isSnapshotFinished() {
        for (var core : cores) {
            var url = String.format("%s/solr/%s/replication?command=details&wt=json", solrBaseUrl, core);
            var response = getJson(url);
            var details = response.path("details");
            var backup = details.path("backup");
            if (backup.isMissingNode() || backup.isEmpty()) {
                continue;
            }
            // Solr returns backup as a NamedList (flat array): ["key1","val1","key2","val2",...]
            String backupStatus = getNamedListValue(backup, "status");
            if (backupStatus == null) {
                continue;
            }
            if ("In Progress".equalsIgnoreCase(backupStatus)) {
                log.info("Solr backup still in progress for core '{}'", core);
                return false;
            }
            if ("success".equalsIgnoreCase(backupStatus)) {
                continue;
            }
            throw new SolrBackupFailed("Backup failed for core '" + core + "': status=" + backupStatus);
        }
        return true;
    }

    /**
     * Extract a value from Solr's NamedList JSON format: ["key1","val1","key2","val2",...].
     */
    private static String getNamedListValue(JsonNode namedList, String key) {
        if (namedList.isArray()) {
            for (int i = 0; i < namedList.size() - 1; i += 2) {
                if (key.equals(namedList.get(i).asText())) {
                    return namedList.get(i + 1).asText();
                }
            }
        } else if (namedList.isObject()) {
            var node = namedList.get(key);
            return node != null ? node.asText() : null;
        }
        return null;
    }

    private JsonNode getJson(String url) {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
