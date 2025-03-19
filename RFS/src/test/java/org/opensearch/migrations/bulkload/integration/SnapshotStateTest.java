package org.opensearch.migrations.bulkload.integration;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.BulkDocSection;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SimpleRestoreFromSnapshot_ES_7_10;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests focused on setting up different snapshot states and then verifying the behavior of RFS towards the target cluster
 * This should move to the CreateSnapshot project
 */
@Disabled("Temporarily disabled to unblock the solutions pipeline")
public class SnapshotStateTest {

    @TempDir
    private File localDirectory;
    private SearchClusterContainer cluster;
    private ClusterOperations operations;
    private SimpleRestoreFromSnapshot_ES_7_10 srfs;
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Class<List<BulkDocSection>> listOfBulkDocSectionType = (Class)List.class;

    @BeforeEach
    public void setUp() throws Exception {
        // Start the cluster for testing
        cluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
        cluster.start();

        // Configure operations and rfs implementation
        operations = new ClusterOperations(cluster);
        operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "test-repo");
        srfs = new SimpleRestoreFromSnapshot_ES_7_10();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.close();
    }

    @Test
    public void SingleSnapshot_SingleDocument() throws Exception {
        // Setup
        final var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        final var indexName = "my-index";
        final var document1Id = "doc1";
        final var document1Body = "   \n {\n\"fo$o\":\"bar\"\n}\t \n"; // Verify that we trim and remove newlines
        operations.createDocument(indexName, document1Id, document1Body);

        final var snapshotName = "snapshot-1";
        final var repoName = "test-repo";
        operations.takeSnapshot(repoName, snapshotName, indexName);

        final File snapshotCopy = new File(localDirectory + "/snapshotCopy");
        cluster.copySnapshotData(snapshotCopy.getAbsolutePath());

        final var unpackedShardDataDir = Path.of(localDirectory.getAbsolutePath() + "/unpacked-shard-data");
        final var indices = srfs.extractSnapshotIndexData(
            snapshotCopy.getAbsolutePath(),
            snapshotName,
            unpackedShardDataDir
        );

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client, testContext.createReindexContext());

        // Validation
        final var docsCaptor = ArgumentCaptor.forClass(listOfBulkDocSectionType);
        verify(client, times(1)).sendBulkRequest(eq(indexName), docsCaptor.capture(), any());
        final var document = docsCaptor.getValue().get(0);
        assertThat(document.getDocId(), equalTo(document1Id));
        assertThat(document.asBulkIndexString(), allOf(containsString(document1Id), containsString("{\"fo$o\":\"bar\"}")));

        verifyNoMoreInteractions(client);
    }

    @Test
    public void SingleSnapshot_SingleDocument_Then_DeletedDocument() throws Exception {
        // Setup
        final var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        final var indexName = "my-index-with-deleted-item";
        final var document1Id = "doc1-going-to-be-deleted";
        final var document1Body = "{\"foo\":\"bar\"}";
        operations.createDocument(indexName, document1Id, document1Body);
        operations.deleteDocument(indexName, document1Id, null);
        final var snapshotName = "snapshot-delete-item";
        var repoName = "test-repo";
        operations.takeSnapshot(repoName, snapshotName, indexName);

        final File snapshotCopy = new File(localDirectory + "/snapshotCopy");
        cluster.copySnapshotData(snapshotCopy.getAbsolutePath());

        final var unpackedShardDataDir = Path.of(localDirectory.getAbsolutePath() + "/unpacked-shard-data");
        final var indices = srfs.extractSnapshotIndexData(
            snapshotCopy.getAbsolutePath(),
            snapshotName,
            unpackedShardDataDir
        );

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client, testContext.createReindexContext());

        // Validation
        verify(client, times(0)).sendBulkRequest(eq(indexName), any(), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    public void SingleSnapshot_SingleDocument_Then_UpdateDocument() throws Exception {
        // Setup
        final var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        final var indexName = "my-index-with-updated-item";
        final var document1Id = "doc1-going-to-be-updated";
        final var document1BodyOriginal = "{\"foo\":\"bar\"}";
        operations.createDocument(indexName, document1Id, document1BodyOriginal);
        final var document1BodyUpdated = "{\"actor\":\"troy mcclure\"}";
        operations.createDocument(indexName, document1Id, document1BodyUpdated);

        final var snapshotName = "snapshot-delete-item";
        final var repoName = "test-repo";
        operations.takeSnapshot(repoName, snapshotName, indexName);

        final File snapshotCopy = new File(localDirectory + "/snapshotCopy");
        cluster.copySnapshotData(snapshotCopy.getAbsolutePath());

        final var unpackedShardDataDir = Path.of(localDirectory.getAbsolutePath() + "/unpacked-shard-data");
        final var indices = srfs.extractSnapshotIndexData(
            snapshotCopy.getAbsolutePath(),
            snapshotName,
            unpackedShardDataDir
        );

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client, testContext.createReindexContext());

        // Validation
        final var docsCaptor = ArgumentCaptor.forClass(listOfBulkDocSectionType);
        verify(client, times(1)).sendBulkRequest(eq(indexName), docsCaptor.capture(), any());

        assertThat("Only one document, the one that was updated", docsCaptor.getValue().size(), equalTo(1));
        final var document = docsCaptor.getValue().get(0);
        assertThat(document.getDocId(), equalTo(document1Id));
        assertThat(document.asBulkIndexString(), not(containsString(document1BodyOriginal)));

        verifyNoMoreInteractions(client);
    }
}
