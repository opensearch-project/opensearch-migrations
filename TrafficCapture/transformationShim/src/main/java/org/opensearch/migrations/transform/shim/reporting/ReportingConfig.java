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

    public boolean enabled = true;
    @JsonProperty("include_request_body") public boolean includeRequestBody;
    public SinkConfig sink;

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
    public static class SinkConfig {
        public String type;
        public OpenSearchSinkConfig opensearch;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenSearchSinkConfig {
        public String uri;
        @JsonProperty("index_prefix") public String indexPrefix = "shim-metrics";
        @JsonProperty("bulk_size") public int bulkSize = 100;
        @JsonProperty("flush_interval_ms") public long flushIntervalMs = 5000;
        public AuthConfig auth;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthConfig {
        public String username;
        public String password;
        public TlsConfig tls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TlsConfig {
        public boolean insecure;
    }
}
