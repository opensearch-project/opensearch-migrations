package com.rfs.integration;

import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.lucene.util.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.rfs.common.ConnectionDetails;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.hamcrest.CoreMatchers.equalTo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class SnapshotStateTest {

     
    private static final Logger logger = LoggerFactory.getLogger(SnapshotStateTest.class);

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
                .withLogConsumer(new Slf4jLogConsumer(logger))
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
        System.out.println("Container Logs:\n" + clusterContainer.getLogs());

        httpClient.close();
        clusterContainer.stop();
        IOUtils.rm(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
    }

    public void createDocument(final String index, final String docId, final String body) throws IOException {
        var indexDocumentRequset = new HttpPut(clusterUrl + "/" + index + "/_doc/" + docId);
        indexDocumentRequset.setEntity(new StringEntity(body));
        indexDocumentRequset.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(indexDocumentRequset)) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        }
    }

    public void takeSnapshot(final String snapshotName, final String indexPattern) throws IOException {
        // Create snapshot
        final var snapshotJson = "{\n" +
                "  \"indices\": \"" + indexPattern + "\",\n" +
                "  \"ignore_unavailable\": true,\n" +
                "  \"include_global_state\": false\n" +
                "}";

        final var createSnapshotRequest = new HttpPut(clusterUrl + "/_snapshot/test-repo/" + snapshotName + "?wait_for_completion=true");
        createSnapshotRequest.setEntity(new StringEntity(snapshotJson));
        createSnapshotRequest.setHeader("Content-Type", "application/json");

        try (var response = httpClient.execute(createSnapshotRequest)) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        }

        // Read snapshot file contents
        for (File file : SNAPSHOT_DIR.listFiles()) {
            System.out.println(file);
        }
        var snapshotFiles = Files.readAllLines(Paths.get(SNAPSHOT_DIR.getAbsolutePath(), "index-0"));
        System.out.println("Snapshot Files: " + snapshotFiles);
    }


    @Test
    public void SingleSnapshot_SingleDocument() throws Exception {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        createDocument("my-index", "doc1", "{\"foo\":\"bar\"}");
        // PSUEDO: Save snapshot1
        final var snapshotName = "snapshot-1";
        takeSnapshot(snapshotName, "my-index");
        // PSUEDO: Start RFS worker reader, point to snapshot1
        var repo = new FileSystemRepo(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
        var snapShotProvider = new SnapshotRepoProvider_ES_7_10(repo);
        List<IndexMetadata.Data> indices = snapShotProvider.getIndicesInSnapshot(snapshotName)
            .stream()
            .map(index -> {
                try {
                    return new IndexMetadataFactory_ES_7_10().fromRepo(repo, snapShotProvider, snapshotName, index.getName());
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());
        
        var sourceFileBasePath = new File("./unpacked");

        for (final IndexMetadata.Data index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                var shardMetadata = new ShardMetadataFactory_ES_7_10().fromRepo(repo, snapShotProvider, snapshotName, index.getName(), shardId);
                SnapshotShardUnpacker.unpack(repo, shardMetadata, Paths.get(sourceFileBasePath.getAbsolutePath()), Integer.MAX_VALUE);
            }
        }

        var targetConnection = mock(ConnectionDetails.class);

        for (final IndexMetadata.Data index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                final var documents = new LuceneDocumentsReader().readDocuments(Paths.get(sourceFileBasePath.getAbsolutePath()), index.getName(), shardId);

                final var finalShardId = shardId; // Define in local context for the lambda
                DocumentReindexer.reindex(index.getName(), documents, targetConnection)
                    .doOnError(error -> logger.error("Error during reindexing: " + error))
                    .doOnSuccess(done -> logger.info("Reindexing completed for index " + index.getName() + ", shard " + finalShardId))
                    // Wait for the shard reindexing to complete before proceeding; fine in this demo script, but
                    // shouldn't be done quite this way in the real RFS Worker.
                    .block();
            }
        }

        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // verify(targetConnection); Not viable


        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }

    public void MultiSnapshot_SingleDocument_Then_DeletedDocument() {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        // PSUEDO: Delete the document
        // PSUEDO: Save snapshot1
        // PSUEDO: Start RFS worker reader, point to the snapshot1
        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }

    public void MultiSnapshot_SingleDocument_Then_UpdateDocument() {
        // Setup
        // PSUEDO: Create an 1 index with 1 document
        // PSUEDO: Update the 1 document
        // PSUEDO: Save snapshot1
        // PSUEDO: Start RFS worker reader, point to the snapshot1
        // PSUEDO: Attach sink to inspect all of the operations performed on the target cluster

        // Action
        // PSUEDO: Start reindex on the worker
        // PSUEDO: Wait until the operations sink has settled with expected operations. 

        // Validation
        // PSUEDO: Read the actions from the sink
        // PSUEDO: Flush all read-only operations from the sink, such as refresh, searchs, etc...
        // PSUEDO: Scan the sink for ONLY the following:
        //    PSUEDO: Should see create index
        //    PSUEDO: Should see bulk put document, with single document which is updated version
        //    PSUEDO: Should see more than one refresh index calls (other misc expected write operations)

        // PSUEDO: Verify no other items were present in the sync
    }
}
