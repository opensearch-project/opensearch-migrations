package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple YAML-like config parser for the reporting framework.
 * Parses the reporting-config.yaml without external YAML dependencies.
 */
public class ReportingConfig {
    private boolean enabled = true;
    private boolean includeRequestBody;
    private String uri;
    private String indexPrefix = "shim-metrics";
    private int bulkSize = 100;
    private long flushIntervalMs = 5000;
    private String username;
    private String password;
    private boolean insecureTls;

    public static ReportingConfig parse(Path path) throws IOException {
        var config = new ReportingConfig();
        Map<String, String> flat = flattenYaml(Files.readString(path));

        if (flat.containsKey("enabled")) {
            config.enabled = Boolean.parseBoolean(flat.get("enabled"));
        }
        if (flat.containsKey("include_request_body")) {
            config.includeRequestBody = Boolean.parseBoolean(flat.get("include_request_body"));
        }
        if (flat.containsKey("sink.opensearch.uri")) {
            config.uri = flat.get("sink.opensearch.uri");
        }
        if (flat.containsKey("sink.opensearch.index_prefix")) {
            config.indexPrefix = flat.get("sink.opensearch.index_prefix");
        }
        if (flat.containsKey("sink.opensearch.bulk_size")) {
            config.bulkSize = Integer.parseInt(flat.get("sink.opensearch.bulk_size"));
        }
        if (flat.containsKey("sink.opensearch.flush_interval_ms")) {
            config.flushIntervalMs = Long.parseLong(flat.get("sink.opensearch.flush_interval_ms"));
        }
        if (flat.containsKey("sink.opensearch.auth.username")) {
            config.username = flat.get("sink.opensearch.auth.username");
        }
        if (flat.containsKey("sink.opensearch.auth.password")) {
            config.password = flat.get("sink.opensearch.auth.password");
        }
        if (flat.containsKey("sink.opensearch.auth.tls.insecure")) {
            config.insecureTls = Boolean.parseBoolean(flat.get("sink.opensearch.auth.tls.insecure"));
        }
        return config;
    }

    /** Simple YAML flattener — handles indentation-based nesting, strips comments. */
    private static Map<String, String> flattenYaml(String content) {
        Map<String, String> result = new HashMap<>();
        String[] prefixStack = new String[20];
        int[] indentStack = new int[20];
        int depth = 0;

        for (String rawLine : content.split("\n")) {
            String noComment = rawLine.contains("#") ? rawLine.substring(0, rawLine.indexOf('#')) : rawLine;
            String trimmed = noComment.trim();
            if (trimmed.isEmpty()) continue;

            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') indent++;

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx <= 0) continue;

            String key = trimmed.substring(0, colonIdx).trim();
            String value = trimmed.substring(colonIdx + 1).trim();

            // Pop stack to find parent
            while (depth > 0 && indentStack[depth - 1] >= indent) depth--;

            if (value.isEmpty()) {
                // This is a parent key
                prefixStack[depth] = key;
                indentStack[depth] = indent;
                depth++;
            } else {
                // This is a leaf key:value
                StringBuilder fullKey = new StringBuilder();
                for (int i = 0; i < depth; i++) {
                    fullKey.append(prefixStack[i]).append(".");
                }
                fullKey.append(key);
                result.put(fullKey.toString(), value);
            }
        }
        return result;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isIncludeRequestBody() { return includeRequestBody; }
    public boolean hasSink() { return uri != null; }
    public String getUri() { return uri; }
    public String getIndexPrefix() { return indexPrefix; }
    public int getBulkSize() { return bulkSize; }
    public long getFlushIntervalMs() { return flushIntervalMs; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isInsecureTls() { return insecureTls; }
}
