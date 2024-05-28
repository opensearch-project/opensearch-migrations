package com.rfs.framework;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;

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

    public void createSnapshotRepository() throws IOException {
        // Create snapshot repository
        final var repositoryJson = "{\n" +
        "  \"type\": \"fs\",\n" +
        "  \"settings\": {\n" +
        "    \"location\": \"/usr/share/elasticsearch/snapshots\",\n" +
        "    \"compress\": false\n" +
        "  }\n" +
        "}";

        final var createRepoRequest = new HttpPut(clusterUrl + "/_snapshot/test-repo");
        createRepoRequest.setEntity(new StringEntity(repositoryJson));
        createRepoRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createRepoRequest)) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        }
    }

    public void createDocument(final String index, final String docId, final String body) throws IOException {
        var indexDocumentRequest = new HttpPut(clusterUrl + "/" + index + "/_doc/" + docId);
        indexDocumentRequest.setEntity(new StringEntity(body));
        indexDocumentRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(indexDocumentRequest)) {
            assertThat(response.getStatusLine().getStatusCode(), anyOf(equalTo(201), equalTo(200)));
        }
    }

    public void deleteDocument(final String index, final String docId) throws IOException {
        var deleteDocumentRequest = new HttpDelete(clusterUrl + "/" + index + "/_doc/" + docId);

        try (var response = httpClient.execute(deleteDocumentRequest)) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        }
    }

    public void takeSnapshot(final String snapshotName, final String indexPattern) throws IOException {
        final var snapshotJson = "{\n" +
                "  \"indices\": \"" + indexPattern + "\",\n" +
                "  \"ignore_unavailable\": true,\n" +
                "  \"include_global_state\": true\n" +
                "}";

        final var createSnapshotRequest = new HttpPut(clusterUrl + "/_snapshot/test-repo/" + snapshotName + "?wait_for_completion=true");
        createSnapshotRequest.setEntity(new StringEntity(snapshotJson));
        createSnapshotRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createSnapshotRequest)) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        }
    }
}
