package com.rfs.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Operations to perform on a cluster with basic builtin success validation
 */
public class ClusterOperations {

    private final String clusterUrl;
    private final CloseableHttpClient httpClient;

    public ClusterOperations(final String clusterUrl) {
        this.clusterUrl = clusterUrl;
        httpClient = HttpClients.createDefault();
    }

    public void createSnapshotRepository(final String repoPath) throws IOException {
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

        final var createRepoRequest = new HttpPut(clusterUrl + "/_snapshot/test-repo");
        createRepoRequest.setEntity(new StringEntity(repositoryJson));
        createRepoRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createRepoRequest)) {
            assertThat(response.getCode(), equalTo(200));
        }
    }

    public void createDocument(final String index, final String docId, final String body) throws IOException {
        var indexDocumentRequest = new HttpPut(clusterUrl + "/" + index + "/_doc/" + docId);
        indexDocumentRequest.setEntity(new StringEntity(body));
        indexDocumentRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(indexDocumentRequest)) {
            assertThat(response.getCode(), anyOf(equalTo(201), equalTo(200)));
        }
    }

    public void deleteDocument(final String index, final String docId) throws IOException {
        var deleteDocumentRequest = new HttpDelete(clusterUrl + "/" + index + "/_doc/" + docId);

        try (var response = httpClient.execute(deleteDocumentRequest)) {
            assertThat(response.getCode(), equalTo(200));
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

    public void takeSnapshot(final String snapshotName, final String indexPattern) throws IOException {
        final var snapshotJson = "{\n"
            + "  \"indices\": \""
            + indexPattern
            + "\",\n"
            + "  \"ignore_unavailable\": true,\n"
            + "  \"include_global_state\": true\n"
            + "}";

        final var createSnapshotRequest = new HttpPut(
            clusterUrl + "/_snapshot/test-repo/" + snapshotName + "?wait_for_completion=true"
        );
        createSnapshotRequest.setEntity(new StringEntity(snapshotJson));
        createSnapshotRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createSnapshotRequest)) {
            assertThat(response.getCode(), equalTo(200));
        }
    }

    /**
     * Creates an ES6 legacy template, intended for use on only ES 6 clusters
     */
    @SneakyThrows
    public void createES6LegacyTemplate(final String templateName, final String pattern) throws IOException {
        final var templateJson = "{\r\n" + //
            "  \"index_patterns\": [\r\n" + //
            "    \"" + pattern + "\"\r\n" + //
            "  ],\r\n" + //
            "  \"settings\": {\r\n" + //
            "    \"number_of_shards\": 1\r\n" + //
            "  },\r\n" + //
            "  \"aliases\": {\r\n" + //
            "    \"alias1\": {}\r\n" + //
            "  },\r\n" + //
            "  \"mappings\": {\r\n" + //
            "    \"_doc\": {\r\n" + //
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

        final var createRepoRequest = new HttpPut(this.clusterUrl + "/_template/" + templateName);
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
     * Creates an ES7 legacy template, intended for use on only ES 7.8+ clusters
     */
    @SneakyThrows
    public void createES7Templates(
        final String componentTemplateName,
        final String indexTemplateName,
        final String fieldName,
        final String indexPattern
    ) throws IOException {
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
            + "        \"alias1\": {}"
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
}
