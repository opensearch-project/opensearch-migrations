package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportingConfig {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private boolean enabled = true;
    @JsonProperty("include_request_body") private boolean includeRequestBody;
    private SinkConfig sink;

    public static ReportingConfig parse(Path path) throws IOException {
        return YAML_MAPPER.readValue(path.toFile(), ReportingConfig.class);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isIncludeRequestBody() { return includeRequestBody; }
    public boolean hasSink() { return sink != null && sink.opensearch != null; }
    public String getUri() { return sink.opensearch.uri; }
    public String getIndexPrefix() { return sink.opensearch.indexPrefix; }
    public int getBulkSize() { return sink.opensearch.bulkSize; }
    public long getFlushIntervalMs() { return sink.opensearch.flushIntervalMs; }
    public String getUsername() { return sink.opensearch.auth != null ? sink.opensearch.auth.username : null; }
    public String getPassword() { return sink.opensearch.auth != null ? sink.opensearch.auth.password : null; }
    public boolean isInsecureTls() {
        return sink.opensearch.auth != null && sink.opensearch.auth.tls != null && sink.opensearch.auth.tls.insecure;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SinkConfig {
        String type;
        OpenSearchSinkConfig opensearch;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OpenSearchSinkConfig {
        String uri;
        @JsonProperty("index_prefix") String indexPrefix = "shim-metrics";
        @JsonProperty("bulk_size") int bulkSize = 100;
        @JsonProperty("flush_interval_ms") long flushIntervalMs = 5000;
        AuthConfig auth;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AuthConfig {
        String username;
        String password;
        TlsConfig tls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TlsConfig {
        boolean insecure;
    }
}
