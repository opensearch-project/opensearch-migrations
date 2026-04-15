package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * HTTP client for Solr that uses {@link ConnectionContext} for authentication,
 * sharing the same auth path (Basic Auth, SigV4, etc.) as the Elasticsearch side.
 */
@Slf4j
public class SolrHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final ConnectionContext connectionContext;

    public SolrHttpClient(ConnectionContext connectionContext) {
        this.connectionContext = connectionContext;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** GET a URL and parse the response as JSON. Throws on non-200 responses. */
    public JsonNode getJson(String url) {
        return getJson(url, Duration.ofSeconds(30));
    }

    /** GET a URL and parse the response as JSON with a custom timeout. */
    public JsonNode getJson(String url, Duration timeout) {
        var body = getString(url, timeout);
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new SolrRequestException("Failed to parse JSON from " + url, e);
        }
    }

    /** GET a URL and return the response body as a string. Throws on non-200 responses. */
    public String getString(String url, Duration timeout) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(timeout);

        applyAuth(builder, url);

        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new SolrRequestException(
                    "Solr authentication failed (HTTP " + response.statusCode() + ") for " + url);
            }
            if (response.statusCode() != 200) {
                throw new SolrRequestException(
                    "Solr returned HTTP " + response.statusCode() + " for " + url
                    + " — body: " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new SolrRequestException("Failed to communicate with Solr: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrRequestException("Interrupted while communicating with Solr");
        }
    }

    /**
     * GET a URL and return the raw HttpResponse (for status-code-only checks like isSolrCloud).
     */
    public HttpResponse<String> getRaw(String url, Duration timeout)
        throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(timeout);
        applyAuth(builder, url);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void applyAuth(HttpRequest.Builder builder, String url) {
        var transformer = connectionContext.getRequestTransformer();
        Map<String, List<String>> headers = new HashMap<>();
        var transformed = transformer.transform("GET", URI.create(url).getPath(), headers, Mono.just(ByteBuffer.allocate(0)))
            .block();
        if (transformed != null) {
            transformed.getHeaders().forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));
        }
    }

    public static class SolrRequestException extends RuntimeException {
        public SolrRequestException(String message) {
            super(message);
        }

        public SolrRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
