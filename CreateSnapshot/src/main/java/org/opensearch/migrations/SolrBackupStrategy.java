package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator;
import org.opensearch.migrations.bulkload.solr.SolrStandaloneBackupCreator;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Backup strategy for Solr sources. Handles both SolrCloud (Collections API)
 * and standalone (Core Admin / replication API) modes.
 */
@Slf4j
public class SolrBackupStrategy implements SourceBackupStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final CreateSnapshot.Args args;

    public SolrBackupStrategy(CreateSnapshot.Args args) {
        this.args = args;
    }

    @Override
    public void run() {
        var backupLocation = args.fileSystemRepoPath != null ? args.fileSystemRepoPath : args.s3RepoUri;
        var solrUrl = args.sourceArgs.toConnectionContext().getUri().toString();
        var username = args.sourceArgs.getUsername();
        var password = args.sourceArgs.getPassword();

        if (args.solrCollections.isEmpty()) {
            try {
                args.solrCollections = discoverCollections(solrUrl, username, password);
                log.info("Auto-discovered {} Solr collection(s): {}", args.solrCollections.size(), args.solrCollections);
            } catch (Exception e) {
                throw new ParameterException("Failed to discover Solr collections: " + e.getMessage());
            }
        }

        if (isSolrCloud(solrUrl, username, password)) {
            runCloudBackup(solrUrl, backupLocation, username, password);
        } else {
            runStandaloneBackup(solrUrl, backupLocation, username, password);
        }
    }

    // ---- Cloud vs standalone detection ----

    static boolean isSolrCloud(String solrUrl, String username, String password) {
        try {
            var response = httpGet(solrUrl + "/solr/admin/collections?action=LIST&wt=json",
                username, password, Duration.ofSeconds(5));
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

    static List<String> discoverCollections(String solrUrl, String username, String password) throws IOException {
        // Try SolrCloud Collections API first
        try {
            var json = httpGetBody(solrUrl + "/solr/admin/collections?action=LIST&wt=json", username, password);
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
        var json = httpGetBody(solrUrl + "/solr/admin/cores?action=STATUS&wt=json", username, password);
        return objectFieldKeys(MAPPER.readTree(json), "status");
    }

    /**
     * Find the first occurrence of {@code fieldName} whose value is a JSON object,
     * and return that object's top-level keys. Skips non-object occurrences
     * (e.g. {@code "status":0} in Solr's responseHeader).
     */
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

    private void runCloudBackup(String solrUrl, String backupLocation, String username, String password) {
        log.info("Detected SolrCloud — using Collections API backup");
        var creator = new SolrSnapshotCreator(
            solrUrl, args.snapshotName, backupLocation,
            args.solrCollections, username, password, args.snapshotRepoName
        );
        creator.registerRepo();
        creator.createSnapshot();
        waitForCompletion(creator::isSnapshotFinished);
    }

    private void runStandaloneBackup(String solrUrl, String backupLocation, String username, String password) {
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
            args.solrCollections, username, password, repositoryName
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

    // ---- HTTP helpers ----

    private static HttpResponse<String> httpGet(String url, String username, String password, Duration timeout)
        throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(timeout);
        if (username != null && password != null) {
            builder.header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String httpGetBody(String url, String username, String password) throws IOException {
        try {
            var response = httpGet(url, username, password, Duration.ofSeconds(10));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during HTTP request to " + url, e);
        }
    }
}
