package org.opensearch.migrations.bulkload.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;

import lombok.SneakyThrows;
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

        final var createRepoRequest = new HttpPut(clusterUrl + "/_snapshot/" + repoName);
        createRepoRequest.setEntity(new StringEntity(repositoryJson));
        createRepoRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createRepoRequest)) {
            assertThat(response.getCode(), equalTo(200));
        }
    }

    @SneakyThrows
    public void restoreSnapshot(final String repository, final String snapshotName) {
        var restoreRequest = new HttpPost(clusterUrl + "/_snapshot/" + repository + "/" + snapshotName + "/_restore"+ "?wait_for_completion=true");
        restoreRequest.setHeader("Content-Type", "application/json");
        restoreRequest.setEntity(new StringEntity("{}"));

        try (var response = httpClient.execute(restoreRequest)) {
            assertThat(response.getCode(), anyOf(equalTo(200), equalTo(202)));
        }
    }

    public void createDocument(final String index, final String docId, final String body) {
        createDocument(index, docId, body, null, defaultDocType());
    }

    @SneakyThrows
    public void createDocument(final String index, final String docId, final String body, String routing, String type) {
        var response = put("/" + index + "/" + Optional.ofNullable(type).orElse(defaultDocType()) + "/" + docId + "?routing=" + routing, body);
        assertThat(response.getValue(), response.getKey(), anyOf(equalTo(201), equalTo(200)));
    }

    public void deleteDocument(final String index, final String docId) throws IOException {
        var deleteDocumentRequest = new HttpDelete(clusterUrl + "/" + index + "/" + defaultDocType() + "/" + docId);

        try (var response = httpClient.execute(deleteDocumentRequest)) {
            assertThat(response.getCode(), equalTo(200));
        }
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

    @SneakyThrows
    public void createIndex(final String index, final String body) {
        var createIndexRequest = new HttpPut(clusterUrl + "/" + index);
        createIndexRequest.setEntity(new StringEntity(body));
        createIndexRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createIndexRequest)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            assertThat(responseBody, response.getCode(), anyOf(equalTo(201), equalTo(200)));
        }
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
            return Map.entry(response.getCode(), responseBody);
        }
    }

    @SneakyThrows
    public Map.Entry<Integer, String> get(final String path) {
        final var getRequest = new HttpGet(clusterUrl + path);

        try (var response = httpClient.execute(getRequest)) {
            var responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
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

        final var createSnapshotRequest = new HttpPut(
            clusterUrl + "/_snapshot/" + repoName + "/" + snapshotName + "?wait_for_completion=true"
        );
        createSnapshotRequest.setEntity(new StringEntity(snapshotJson));
        createSnapshotRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createSnapshotRequest)) {
            assertThat(response.getCode(), equalTo(200));
        }
    }

    /**
     * Creates a legacy template
     */
    @SneakyThrows
    public void createLegacyTemplate(final String templateName, final String pattern) throws IOException {
        var matchPatternClause = VersionMatchers.isES_5_X.test(clusterVersion)
            ? "\"template\":\"" + pattern + "\","
            : "\"index_patterns\": [\r\n" + //
            "    \"" + pattern + "\"\r\n" + //
            "  ],\r\n";
        final var templateJson = "{\r\n" + //
            "  " + matchPatternClause + //
            "  \"settings\": {\r\n" + //
            "    \"number_of_shards\": 1\r\n" + //
            "  },\r\n" + //
            "  \"aliases\": {\r\n" + //
            "    \"alias_legacy\": {}\r\n" + //
            "  },\r\n" + //
            "  \"mappings\": {\r\n" + //
            "    \"" + defaultDocType() + "\": {\r\n" + //
            "      \"_source\": {\r\n" + //
            "        \"enabled\": true\r\n" + //
            "      },\r\n" + //
            "      \"properties\": {\r\n" + //
            "        \"host_name\": {\r\n" + //
            "          \"type\": \"keyword\"\r\n" + //
            "        },\r\n" + //
            "        \"created_at\": {\r\n" + //
            "          \"type\": \"date\",\r\n" + //
            "          \"format\": \"EEE MMM dd HH:mm:ss Z yyyy\"\r\n" + //
            "        }\r\n" + //
            "      }\r\n" + //
            "    }\r\n" + //
            "  }\r\n" + //
            "}";

        var extraParameters = VersionMatchers.isES_5_X.test(clusterVersion) ? "" : "?include_type_name=true";
        final var createRepoRequest = new HttpPut(this.clusterUrl + "/_template/" + templateName + extraParameters);
        createRepoRequest.setEntity(new StringEntity(templateJson));
        createRepoRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createRepoRequest)) {
            assertThat(
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                response.getCode(),
                equalTo(200)
            );
        }
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

        final var compTempUrl = clusterUrl + "/_component_template/" + componentTemplateName;
        final var createCompTempRequest = new HttpPut(compTempUrl);
        createCompTempRequest.setEntity(new StringEntity(componentTemplateJson));
        createCompTempRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createCompTempRequest)) {
            assertThat(
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                    response.getCode(),
                    equalTo(200)
            );
        }

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

        final var indexTempUrl = clusterUrl + "/_index_template/" + indexTemplateName;
        final var createIndexTempRequest = new HttpPut(indexTempUrl);
        createIndexTempRequest.setEntity(new StringEntity(indexTemplateJson));
        createIndexTempRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createIndexTempRequest)) {
            assertThat(
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                    response.getCode(),
                    equalTo(200)
            );
        }
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

        final var indexTempUrl = clusterUrl + "/_index_template/" + indexTemplateName;
        final var createIndexTempRequest = new HttpPut(indexTempUrl);
        createIndexTempRequest.setEntity(new StringEntity(indexTemplateJson));
        createIndexTempRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createIndexTempRequest)) {
            assertThat(
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                response.getCode(),
                equalTo(200)
            );
        }
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

        final var aliasRequest = new HttpPost(this.clusterUrl + "/_aliases");
        aliasRequest.setEntity(new StringEntity(requestBodyJson));
        aliasRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(aliasRequest)) {
            assertThat(
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                response.getCode(),
                equalTo(200)
            );
        }
    }

    private String defaultDocType() {
        if (VersionMatchers.isES_5_X.test(clusterVersion)) {
            return "doc";
        } else {
            return "_doc";
        }
    }
}
