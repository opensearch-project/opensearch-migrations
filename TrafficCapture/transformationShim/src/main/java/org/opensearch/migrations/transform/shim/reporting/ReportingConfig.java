package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportingConfig {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private boolean enabled = true;
    @JsonProperty("include_request_body") private boolean includeRequestBody;
    private SinkConfig sink;

    public static ReportingConfig parse(Path path) throws IOException {
        return JSON_MAPPER.readValue(path.toFile(), ReportingConfig.class);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setIncludeRequestBody(boolean includeRequestBody) { this.includeRequestBody = includeRequestBody; }
    public void setSink(SinkConfig sink) { this.sink = sink; }

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

    /** Build a {@link ConnectionContext} from this config's sink settings. */
    public ConnectionContext toConnectionContext() {
        var auth = sink.getOpensearch().getAuth();
        return new ConnectionContext.IParams() {
            @Override public String getHost() { return getUri(); }
            @Override public String getUsername() { return auth != null ? auth.getUsername() : null; }
            @Override public String getPassword() { return auth != null ? auth.getPassword() : null; }
            @Override public String getAwsRegion() { return auth != null ? auth.getAwsRegion() : null; }
            @Override public String getAwsServiceSigningName() { return auth != null ? auth.getAwsServiceSigningName() : null; }
            @Override public Path getCaCert() { return null; }
            @Override public Path getClientCert() { return null; }
            @Override public Path getClientCertKey() { return null; }
            @Override public boolean isDisableCompression() { return true; }
            @Override public boolean isInsecure() { return isInsecureTls(); }
        }.toConnectionContext();
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
        @JsonProperty("aws_region") private String awsRegion;
        @JsonProperty("aws_service_signing_name") private String awsServiceSigningName;
        private TlsConfig tls;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getAwsRegion() { return awsRegion; }
        public void setAwsRegion(String awsRegion) { this.awsRegion = awsRegion; }
        public String getAwsServiceSigningName() { return awsServiceSigningName; }
        public void setAwsServiceSigningName(String awsServiceSigningName) { this.awsServiceSigningName = awsServiceSigningName; }
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
