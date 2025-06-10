package org.opensearch.migrations.bulkload.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Operations to perform on a cluster with basic builtin success validation
 */
@Slf4j
public class ClusterOperations {

    private final String clusterUrl;
    private final Version clusterVersion;
    private final CloseableHttpClient httpClient;

    public ClusterOperations(final SearchClusterContainer cluster) {
        this.clusterUrl = cluster.getUrl();
        this.clusterVersion = cluster.getContainerVersion().getVersion();
        httpClient = HttpClients.createDefault();
    }

    @SneakyThrows
    public void createSnapshotRepository(final String repoPath, final String repoName) {
        // Create snapshot repository
        final var repositoryJson = "{\n"
            + "  \"type\": \"fs\",\n"
            + "  \"settings\": {\n"
            + "    \"location\": \""
            + repoPath
            + "\",\n"
            + "    \"compress\": false\n"
            + "  }\n"
            + "}";

        var response = put("/_snapshot/" + repoName, repositoryJson);
        assertThat(response.getKey(), equalTo(200));
    }

    @SneakyThrows
    public void restoreSnapshot(final String repository, final String snapshotName) {
        var response = post("/_snapshot/" + repository + "/" + snapshotName + "/_restore"+ "?wait_for_completion=true", "{}");
        assertThat(response.getKey(), anyOf(equalTo(200), equalTo(202)));
    }

    public void deleteSnapshot(final String repository, final String snapshotName) {
        var response = delete("/_snapshot/" + repository + "/" + snapshotName);
        assertThat(response.getKey(), equalTo(200));
    }

    public void createDocument(final String index, final String docId, final String body) {
        createDocument(index, docId, body, null, null);
    }

    public void createDocument(final String index, final String docId, final String body, final String routing, final String type) {
        var response = put("/" + index + "/" + docTypePathOrDefault(type) + docId + "?routing=" + routing, body);
        assertThat(response.getValue(), response.getKey(), anyOf(equalTo(201), equalTo(200)));
    }

    public void deleteDocument(final String index, final String docId, final String type) {
        deleteDocument(index, docId, null, type);
    }

    public void deleteDocument(final String index, final String docId, final String routing, final String type) {
        var response = delete("/" + index + "/" + docTypePathOrDefault(type) + docId + "?routing=" + routing);
        assertThat(response.getKey(), equalTo(200));
    }

    public void createIndexWithMappings(final String index, final String mappings) {
        var body = "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 5," +
                "      \"number_of_replicas\": 0" +
                "    }" +
                "  }," +
                "  \"mappings\": " + mappings +
                "}";
        createIndex(index, body);
    }

    public void createIndex(final String index) {
        var body = "{" + //
        "  \"settings\": {" + //
        "    \"index\": {" + //
        "      \"number_of_shards\": 5," + //
        "      \"number_of_replicas\": 0" + //
        "    }" + //
        "  }" + //
        "}";
        createIndex(index, body);
    }

    // Bloom Filter is a relatively new default index setting introduced in ES 8x
    @SneakyThrows
    public void disableBloom(final String index) {
        final String body = "{" +
                "  \"index\": {" +
                "    \"bloom_filter_for_id_field\": {" +
                "      \"enabled\": false" +
                "    }" +
                "  }" +
                "}";

        var response = put("/" + index + "/_settings", body);
        assertThat(response.getKey(), equalTo(200));
    }

    @SneakyThrows
    public void createIndex(final String index, final String body) {
        var response = put("/" + index, body);
        assertThat(response.getKey(), anyOf(equalTo(201), equalTo(200)));

        // Automatically apply ES 8.x specific index tweaks
        if (VersionMatchers.isES_8_X.test(clusterVersion)) {
            log.info("Cluster is ES 8.x — applying disableBloom setting on index: {}", index);
            disableBloom(index);
        }
    }

    @SneakyThrows
    public Map.Entry<Integer, String> post(final String path, final String body) {
        final var postRequest = new HttpPost(clusterUrl + path);
        if (body != null) {
            postRequest.setEntity(new StringEntity(body));
            postRequest.setHeader("Content-Type", "application/json");
        }
        try (var response = httpClient.execute(postRequest)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            logHttpRequestResponse("POST", path, response.getCode(), responseBody, null);
            return Map.entry(response.getCode(), responseBody);
        }
    }

    private static String truncate(String input, int maxLength) {
        return input != null && input.length() > maxLength ? input.substring(0, maxLength) + "..." : input;
    }

    private static void logHttpRequestResponse(
            String method, String path, int statusCode, String responseBody, String body) {
        log.atInfo()
                .setMessage("{} {}\n{}\nResponse: {}\n{}")
                .addArgument(method)
                .addArgument(path)
                .addArgument(() -> body != null ? truncate(body, 1000) : "")
                .addArgument(statusCode)
                .addArgument(() -> truncate(responseBody, 1000))
                .log();
    }


    @SneakyThrows
    public Map.Entry<Integer, String> put(final String path, final String body) {
        final var putRequest = new HttpPut(clusterUrl + path);
        if (body != null) {
            putRequest.setEntity(new StringEntity(body));
            putRequest.setHeader("Content-Type", "application/json");
        }
        try (var response = httpClient.execute(putRequest)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            logHttpRequestResponse("PUT", path, response.getCode(), responseBody, body);
            return Map.entry(response.getCode(), responseBody);
        }
    }

    @SneakyThrows
    public Map.Entry<Integer, String> get(final String path) {
        final var getRequest = new HttpGet(clusterUrl + path);

        try (var response = httpClient.execute(getRequest)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            logHttpRequestResponse("GET", path, response.getCode(), responseBody, null);
            return Map.entry(response.getCode(), responseBody);
        }
    }

    @SneakyThrows
    public Map.Entry<Integer, String> delete(final String path) {
        final var request = new HttpDelete(clusterUrl + path);

        try (var response = httpClient.execute(request)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            logHttpRequestResponse("DELETE", path, response.getCode(), responseBody, null);
            return Map.entry(response.getCode(), responseBody);
        }
    }

    @SneakyThrows
    public void takeSnapshot(final String repoName, final String snapshotName, final String indexPattern) {
        final var snapshotJson = "{\n"
            + "  \"indices\": \""
            + indexPattern
            + "\",\n"
            + "  \"ignore_unavailable\": true,\n"
            + "  \"include_global_state\": true\n"
            + "}";
        var response = put("/_snapshot/" + repoName + "/" + snapshotName + "?wait_for_completion=true", snapshotJson);
        assertThat(response.getKey(), equalTo(200));
    }

    /**
     * Creates a legacy template
     */
    @SneakyThrows
    public void createLegacyTemplate(final String templateName, final String pattern) throws IOException {
        boolean useTypedMappings = !VersionMatchers.isES_8_X.test(clusterVersion);

        var matchPatternClause = VersionMatchers.isES_5_X.test(clusterVersion)
            ? "\"template\":\"" + pattern + "\","
            : "\"index_patterns\": [\r\n" + //
            "    \"" + pattern + "\"\r\n" + //
            "  ],\r\n";
        final var templateJson = "{\r\n" + //
            "  " + matchPatternClause +
            "  \"settings\": {\r\n" +
            "    \"number_of_shards\": 1\r\n" +
            "  },\r\n" +
            "  \"aliases\": {\r\n" +
            "    \"alias_legacy\": {}\r\n" +
            "  },\r\n" +
            "  \"mappings\": " + (useTypedMappings ? "{\r\n" + //
            "    \"" + defaultDocType() + "\": {\r\n" : "{\r\n") + //
            "      \"_source\": {\r\n" +
            "        \"enabled\": true\r\n" +
            "      },\r\n" +
            "      \"properties\": {\r\n" +
            "        \"host_name\": {\r\n" +
            "          \"type\": \"keyword\"\r\n" +
            "        },\r\n" +
            "        \"created_at\": {\r\n" +
            "          \"type\": \"date\",\r\n" +
            "          \"format\": \"EEE MMM dd HH:mm:ss Z yyyy\"\r\n" +
            "        }\r\n" +
            "      }\r\n" +
            (useTypedMappings ? "    }\r\n" : "") +
            "  }\r\n" +
            "}";

        var extraParameters = (
                VersionMatchers.isES_5_X
                        .or(VersionMatchers.isES_8_X)
                        .or(VersionMatchers.equalOrBetween_ES_6_0_and_6_5)
            ).test(clusterVersion)
                    ? ""
                    : "?include_type_name=true";
        var response = put("/_template/" + templateName + extraParameters, templateJson);

        assertThat(response.getKey(), equalTo(200));
    }

    /**
     * Creates an ES7 component template, intended for use on only ES 7.8+ clusters
     */
    @SneakyThrows
    public void createComponentTemplate(
        final String componentTemplateName,
        final String indexTemplateName,
        final String fieldName,
        final String indexPattern
    ) {
        final var componentTemplateJson = "{"
            + "\"template\": {"
            + "    \"settings\": {"
            + "        \"number_of_shards\": 1,"
            + "        \"number_of_replicas\": 1"
            + "    },"
            + "    \"mappings\": {"
            + "        \"properties\": {"
            + "            \""
            + fieldName
            + "\": {"
            + "                \"type\": \"text\""
            + "            }"
            + "        }"
            + "    },"
            + "    \"aliases\": {"
            + "        \"alias_component\": {}"
            + "    }"
            + "},"
            + "\"version\": 1"
            + "}";

        final var compTempUrl = "/_component_template/" + componentTemplateName;
        var response = put(compTempUrl, componentTemplateJson);
        assertThat(response.getKey(), equalTo(200));

        final var indexTemplateJson = "{"
                + "\"index_patterns\": [\""
                + indexPattern
                + "\"],"
                + "\"composed_of\": [\""
                + componentTemplateName
                + "\"],"
                + "\"priority\": 1,"
                + "\"version\": 1"
                + "}";

        final var indexTempUrl = "/_index_template/" + indexTemplateName;
        var response2 = put(indexTempUrl, indexTemplateJson);
        assertThat(response2.getKey(), equalTo(200));
    }


    /**
     * Creates an ES7 index template, intended for use on only ES 7.8+ clusters
     */
    @SneakyThrows
    public void createIndexTemplate(
        final String indexTemplateName,
        final String fieldName,
        final String indexPattern
    ) {
        final var templateJson = "{"
            + "    \"settings\": {"
            + "        \"number_of_shards\": 1,"
            + "        \"number_of_replicas\": 1"
            + "    },"
            + "    \"mappings\": {"
            + "        \"properties\": {"
            + "            \""
            + fieldName
            + "\": {"
            + "                \"type\": \"text\""
            + "            }"
            + "        }"
            + "    },"
            + "    \"aliases\": {"
            + "        \"alias_index\": {}"
            + "    }"
            + "}";

        final var indexTemplateJson = "{"
            + "\"index_patterns\": [\""
            + indexPattern
            + "\"],"
            + "\"template\":" + templateJson + ","
            + "\"priority\": 1,"
            + "\"version\": 1"
            + "}";

        final var indexTempUrl = "/_index_template/" + indexTemplateName;
        var response = put(indexTempUrl, indexTemplateJson);

        assertThat(response.getKey(), equalTo(200));
    }

    @SneakyThrows
    public void createAlias(String aliasName, String indexPattern) {
        final var requestBodyJson = "{\r\n" + //
            "  \"actions\": [\r\n" + //
            "    {\r\n" + //
            "      \"add\": {\r\n" + //
            "        \"index\": \"" + indexPattern + "\",\r\n" + //
            "        \"alias\": \"" + aliasName + "\"\r\n" + //
            "      }\r\n" + //
            "    }\r\n" + //
            "  ]\r\n" + //
            "}";

        var response = post("/_aliases", requestBodyJson);
        assertThat(response.getKey(), equalTo(200));
    }

    private String defaultDocType() {
        if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_2_X).or(VersionMatchers.isES_6_0_to_6_1).test(clusterVersion)) {
            return "doc";
        }
        return "_doc";
    }

    private String docTypePathOrDefault(final String typeOverride) {
        var defaultDocType = defaultDocType();
        if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_2_X).test(clusterVersion)) {
            return Optional.ofNullable(typeOverride).orElse(defaultDocType) + "/";
        } else {
            return defaultDocType + "/";
        }
    }
}
