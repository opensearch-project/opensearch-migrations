package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight HTTP client for Solr's JSON API.
 * Supports optional Basic Auth and retry with exponential backoff.
 */
@Slf4j
public class SolrClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 10_000;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final String authHeader;
    private final int maxRetries;

    public SolrClient(String baseUrl) {
        this(baseUrl, null, null, DEFAULT_MAX_RETRIES);
    }

    public SolrClient(String baseUrl, String username, String password) {
        this(baseUrl, username, password, DEFAULT_MAX_RETRIES);
    }

    public SolrClient(String baseUrl, String username, String password, int maxRetries) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.authHeader = buildAuthHeader(username, password);
        this.maxRetries = maxRetries;
    }

    private static String buildAuthHeader(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        var credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    /** List all collections (SolrCloud) or cores (standalone fallback). */
    public List<String> listCollections() throws IOException {
        try {
            var node = getJson(baseUrl + "/solr/admin/collections?action=LIST&wt=json");
            var collections = node.get("collections");
            if (collections != null && collections.isArray()) {
                return MAPPER.convertValue(collections, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            }
        } catch (Exception e) {
            log.debug("Collections API not available, falling back to cores API", e);
        }

        var node = getJson(baseUrl + "/solr/admin/cores?action=STATUS&wt=json");
        var status = node.get("status");
        if (status != null && status.isObject()) {
            var names = new java.util.ArrayList<String>();
            status.fieldNames().forEachRemaining(names::add);
            return names;
        }
        return List.of();
    }

    /** Get the schema fields for a collection/core. */
    public JsonNode getSchema(String collection) throws IOException {
        return getJson(baseUrl + "/solr/" + collection + "/schema?wt=json");
    }

    /** Query documents with cursor-based pagination. */
    public SolrQueryResponse query(String collection, String cursorMark, int rows) throws IOException {
        var url = baseUrl + "/solr/" + collection + "/select"
            + "?q=*:*&wt=json&sort=id+asc"
            + "&rows=" + rows
            + "&cursorMark=" + java.net.URLEncoder.encode(cursorMark, java.nio.charset.StandardCharsets.UTF_8);
        var node = getJson(url);
        var response = node.get("response");
        var docs = response.get("docs");
        var nextCursorMark = node.get("nextCursorMark").asText();
        long numFound = response.get("numFound").asLong();
        return new SolrQueryResponse(docs, nextCursorMark, numFound);
    }

    /** Get collection/core status including shard info. */
    public JsonNode getClusterState(String collection) throws IOException {
        try {
            var node = getJson(baseUrl + "/solr/admin/collections?action=CLUSTERSTATUS&collection=" + collection + "&wt=json");
            return node.path("cluster").path("collections").path(collection);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode getJson(String url) throws IOException {
        return getJsonWithRetry(url, 0);
    }

    private JsonNode getJsonWithRetry(String url, int attempt) throws IOException {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        var request = builder.build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IOException("Solr authentication failed (HTTP " + response.statusCode() + ") for " + url);
            }
            if (response.statusCode() >= 500 && attempt < maxRetries) {
                log.warn("Solr returned HTTP {} for {}, retrying ({}/{})", response.statusCode(), url, attempt + 1, maxRetries);
                sleepWithJitter(attempt);
                return getJsonWithRetry(url, attempt + 1);
            }
            if (response.statusCode() != 200) {
                throw new IOException("Solr returned HTTP " + response.statusCode() + " for " + url);
            }
            try (InputStream body = response.body()) {
                return MAPPER.readTree(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while querying Solr", e);
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
            if (attempt < maxRetries) {
                log.warn("Connection failed for {}: {}, retrying ({}/{})", url, e.getMessage(), attempt + 1, maxRetries);
                sleepWithJitter(attempt);
                return getJsonWithRetry(url, attempt + 1);
            }
            throw new IOException("Failed to connect to Solr after " + maxRetries + " retries: " + url, e);
        }
    }

    static void sleepWithJitter(int attempt) {
        var delay = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
        var jittered = delay / 2 + ThreadLocalRandom.current().nextLong(delay / 2);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit close in Java 11+
    }

    /** Response from a Solr /select query. */
    @SuppressWarnings({ "java:S100", "java:S1186" })
    public record SolrQueryResponse(JsonNode docs, String nextCursorMark, long numFound) {}
}
