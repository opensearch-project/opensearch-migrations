package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight HTTP client for Solr's JSON API.
 * Uses java.net.http.HttpClient — no SolrJ dependency.
 */
@Slf4j
public class SolrClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public SolrClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** List all collections (SolrCloud) or cores (standalone). */
    public List<String> listCollections() throws IOException {
        // Try SolrCloud collections API first
        try {
            var node = getJson(baseUrl + "/solr/admin/collections?action=LIST&wt=json");
            var collections = node.get("collections");
            if (collections != null && collections.isArray()) {
                return MAPPER.convertValue(collections, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            }
        } catch (Exception e) {
            log.debug("Collections API not available, trying cores API", e);
        }

        // Fall back to cores API (standalone Solr)
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
            // SolrCloud: use CLUSTERSTATUS
            var node = getJson(baseUrl + "/solr/admin/collections?action=CLUSTERSTATUS&collection=" + collection + "&wt=json");
            return node.path("cluster").path("collections").path(collection);
        } catch (Exception e) {
            // Standalone: single shard
            return null;
        }
    }

    private JsonNode getJson(String url) throws IOException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Solr returned HTTP " + response.statusCode() + " for " + url);
            }
            try (InputStream body = response.body()) {
                return MAPPER.readTree(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while querying Solr", e);
        }
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit close in Java 11+
    }

    /** Response from a Solr /select query. */
    public record SolrQueryResponse(JsonNode docs, String nextCursorMark, long numFound) {
        // Record — no additional methods needed
    }
}
