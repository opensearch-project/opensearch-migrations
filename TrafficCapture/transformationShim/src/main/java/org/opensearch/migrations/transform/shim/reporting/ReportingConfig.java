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
    public boolean hasSink() { return sink != null && sink.getOpensearch() != null; }
    public String getUri() { return sink.getOpensearch().getUri(); }
    public String getIndexPrefix() { return sink.getOpensearch().getIndexPrefix(); }
    public int getBulkSize() { return sink.getOpensearch().getBulkSize(); }
    public long getFlushIntervalMs() { return sink.getOpensearch().getFlushIntervalMs(); }
    public String getUsername() {
        return sink.getOpensearch().getAuth() != null ? sink.getOpensearch().getAuth().getUsername() : null;
    }
    public String getPassword() {
        return sink.getOpensearch().getAuth() != null ? sink.getOpensearch().getAuth().getPassword() : null;
    }
    public boolean isInsecureTls() {
        var os = sink.getOpensearch();
        return os.getAuth() != null && os.getAuth().getTls() != null && os.getAuth().getTls().isInsecure();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SinkConfig {
        private String type;
        private OpenSearchSinkConfig opensearch;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public OpenSearchSinkConfig getOpensearch() { return opensearch; }
        public void setOpensearch(OpenSearchSinkConfig opensearch) { this.opensearch = opensearch; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenSearchSinkConfig {
        private String uri;
        @JsonProperty("index_prefix") private String indexPrefix = "shim-metrics";
        @JsonProperty("bulk_size") private int bulkSize = 100;
        @JsonProperty("flush_interval_ms") private long flushIntervalMs = 5000;
        private AuthConfig auth;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getIndexPrefix() { return indexPrefix; }
        public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }
        public int getBulkSize() { return bulkSize; }
        public void setBulkSize(int bulkSize) { this.bulkSize = bulkSize; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
        public AuthConfig getAuth() { return auth; }
        public void setAuth(AuthConfig auth) { this.auth = auth; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthConfig {
        private String username;
        private String password;
        private TlsConfig tls;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public TlsConfig getTls() { return tls; }
        public void setTls(TlsConfig tls) { this.tls = tls; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TlsConfig {
        private boolean insecure;
        public boolean isInsecure() { return insecure; }
        public void setInsecure(boolean insecure) { this.insecure = insecure; }
    }
}
