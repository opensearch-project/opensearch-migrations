package com.rfs.integration;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.IndexMetadata;
import com.rfs.common.IndexMetadata.Data;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SnapshotStateTest {

     
    private static final Logger logger = LogManager.getLogger(SnapshotStateTest.class);

    private final File SNAPSHOT_DIR = new File("./snapshotRepo");
    private static GenericContainer<?> clusterContainer;
    private static CloseableHttpClient httpClient;
    private static String clusterUrl;

    @SuppressWarnings("resource")
    @BeforeEach
    public void setUp() throws Exception {
        IOUtils.rm(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
        SNAPSHOT_DIR.mkdir();

        clusterContainer = new GenericContainer<>(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:7.10.2"))
                .withExposedPorts(9200, 9300)
                .withEnv("discovery.type", "single-node")
                .withEnv("path.repo", "/usr/share/elasticsearch/snapshots")
                .withFileSystemBind(SNAPSHOT_DIR.getName(), "/usr/share/elasticsearch/snapshots", BindMode.READ_WRITE)
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));

        clusterContainer.start();

        final var address = clusterContainer.getHost();
        final var port = clusterContainer.getMappedPort(9200);
        httpClient = HttpClients.createDefault();
        clusterUrl = "http://" + address + ":" + port;

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

    @AfterEach
    public void tearDown() throws IOException {
        System.err.println("Container Logs:\n" + clusterContainer.getLogs());

        httpClient.close();
        clusterContainer.stop();
        IOUtils.rm(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
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
        // Create snapshot
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

        // Read snapshot file contents
        var filesList = Stream.of(SNAPSHOT_DIR.listFiles())
            .map(f -> f.getName())
            .collect(Collectors.joining(", "));
        logger.info("Snapshot directory contents:\n" + filesList);

        var firstIndexPath = Paths.get(SNAPSHOT_DIR.getAbsolutePath(), "index-0");
        if (Files.exists(firstIndexPath)) {
            logger.info("First index metadata:\n" + Files.readAllLines(firstIndexPath));
        }
    }

    public List<IndexMetadata.Data> extraSnapshotIndexData(final String snapshotName, final Path unpackedShardDataDir) throws Exception {
        IOUtils.rm(unpackedShardDataDir);

        final var repo = new FileSystemRepo(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
        final var snapShotProvider = new SnapshotRepoProvider_ES_7_10(repo);
        final List<IndexMetadata.Data> indices = snapShotProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .map(index -> {
                try {
                    return new IndexMetadataFactory_ES_7_10().fromRepo(repo, snapShotProvider, snapshotName, index.getName());
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());
        
        for (final IndexMetadata.Data index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                var shardMetadata = new ShardMetadataFactory_ES_7_10().fromRepo(repo, snapShotProvider, snapshotName, index.getName(), shardId);
                SnapshotShardUnpacker.unpack(repo, shardMetadata, unpackedShardDataDir, Integer.MAX_VALUE);
            }
        }
        return indices;
    }

    public void updateTargetCluster(final List<IndexMetadata.Data> indices, final Path unpackedShardDataDir, final OpenSearchClient client) throws Exception {
        for (final IndexMetadata.Data index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                final var documents = new LuceneDocumentsReader().readDocuments(unpackedShardDataDir, index.getName(), shardId);

                final var finalShardId = shardId; // Define in local context for the lambda
                DocumentReindexer.reindex(index.getName(), documents, client)
                    .doOnError(error -> logger.error("Error during reindexing: " + error))
                    .doOnSuccess(done -> logger.info("Reindexing completed for index " + index.getName() + ", shard " + finalShardId))
                    // Wait for the shard reindexing to complete before proceeding; fine in this demo script, but
                    // shouldn't be done quite this way in the real RFS Worker.
                    .block();
            }
        }
    }

    public JsonNode asJson(final String data) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(data);
    }

    @Test
    public void SingleSnapshot_SingleDocument() throws Exception {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        final var indexName = "my-index";
        final var document1Id = "doc1";
        final var document1Body = "{\"foo\":\"bar\"}";
        createDocument(indexName, document1Id, document1Body);
        // PSUEDO: Save snapshot1
        final var snapshotName = "snapshot-1";
        takeSnapshot(snapshotName, indexName);
        // PSUEDO: Start RFS worker reader, point to snapshot1
        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = extraSnapshotIndexData(snapshotName, unpackedShardDataDir);

        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster
        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 
        updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        // PSUEDO: Read the actions from the sink
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, allOf(containsString(document1Id), containsString(document1Body)));
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
        verifyNoMoreInteractions(client);
    }

    @Test
    public void MultiSnapshot_SingleDocument_Then_DeletedDocument() throws Exception {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        final var indexName = "my-index-with-deleted-item";
        final var document1Id = "doc1-going-to-be-deleted";
        final var document1Body = "{\"foo\":\"bar\"}";
        createDocument(indexName, document1Id, document1Body);
        // PSUEDO: Delete the document
        deleteDocument(indexName, document1Id);
        // PSUEDO: Save snapshot1
        final var snapshotName = "snapshot-delete-item";
        takeSnapshot(snapshotName, indexName);
        // PSUEDO: Start RFS worker reader, point to snapshot1
        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = extraSnapshotIndexData(snapshotName, unpackedShardDataDir);

        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster
        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 
        updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        // PSUEDO: Read the actions from the sink
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, not(anyOf(containsString(document1Id), containsString(document1Body))));

        // PSUEDO: Verify no other items were present in the sync
        verifyNoMoreInteractions(client);
    }

    @Test
    public void MultiSnapshot_SingleDocument_Then_UpdateDocument() throws Exception {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        final var indexName = "my-index-with-updated-item";
        final var document1Id = "doc1-going-to-be-updated";
        final var document1BodyOrginal = "{\"foo\":\"bar\"}";
        createDocument(indexName, document1Id, document1BodyOrginal);
        // PSUEDO: Update the 1 document
        final var document1BodyUpdated = "{\"actor\":\"troy mcclure\"}";
        createDocument(indexName, document1Id, document1BodyUpdated);
        // PSUEDO: Save snapshot1
        final var snapshotName = "snapshot-delete-item";
        takeSnapshot(snapshotName, indexName);
        // PSUEDO: Start RFS worker reader, point to the snapshot1
        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = extraSnapshotIndexData(snapshotName, unpackedShardDataDir);

        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster
        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 
        updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        // PSUEDO: Read the actions from the sink
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document which is updated version
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, allOf(containsString(document1Id), containsString(document1BodyUpdated), not(containsString(document1BodyOrginal))));
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
        verifyNoMoreInteractions(client);
    }
}
