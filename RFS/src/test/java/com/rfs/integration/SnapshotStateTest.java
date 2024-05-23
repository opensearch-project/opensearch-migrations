package com.rfs.integration;

import org.apache.lucene.util.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.rfs.common.OpenSearchClient;
import com.rfs.framework.ClusterOperations;
import com.rfs.framework.ElasticsearchContainer;
import com.rfs.framework.SimpleRestoreFromSnapshot_ES_7_10;
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
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests focused on setting up different snapshot states and then verifying the behavior of RFS towards the target cluster
 */
public class SnapshotStateTest {

    private final File SNAPSHOT_DIR = new File("./snapshotRepo");
    private ElasticsearchContainer cluster;
    private ClusterOperations operations;
    private SimpleRestoreFromSnapshot_ES_7_10 srfs;

    @BeforeEach
    public void setUp() throws Exception {
        // Make sure the snapshot directory is clean before and after tests run
        IOUtils.rm(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
        SNAPSHOT_DIR.mkdir();

        // Start the cluster for testing
        cluster = new ElasticsearchContainer(ElasticsearchContainer.Version.V7_10_2, SNAPSHOT_DIR.getName());
        cluster.start();

        // Configure operations and rfs implementation
        operations = new ClusterOperations(cluster.getUrl());
        operations.createSnapshotRepository();
        srfs = new SimpleRestoreFromSnapshot_ES_7_10();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.close();
        IOUtils.rm(Path.of(SNAPSHOT_DIR.getAbsolutePath()));
    }

    @Test
    public void SingleSnapshot_SingleDocument() throws Exception {
        // Setup
        final var indexName = "my-index";
        final var document1Id = "doc1";
        final var document1Body = "{\"foo\":\"bar\"}";
        operations.createDocument(indexName, document1Id, document1Body);

        final var snapshotName = "snapshot-1";
        operations.takeSnapshot(snapshotName, indexName);

        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = srfs.extraSnapshotIndexData(SNAPSHOT_DIR.getAbsolutePath(), snapshotName, unpackedShardDataDir);

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, allOf(containsString(document1Id), containsString(document1Body)));

        verifyNoMoreInteractions(client);
    }

    @Test
    @Disabled("https://opensearch.atlassian.net/browse/MIGRATIONS-1746")
    public void SingleSnapshot_SingleDocument_Then_DeletedDocument() throws Exception {
        // Setup
        final var indexName = "my-index-with-deleted-item";
        final var document1Id = "doc1-going-to-be-deleted";
        final var document1Body = "{\"foo\":\"bar\"}";
        operations.createDocument(indexName, document1Id, document1Body);
        operations.deleteDocument(indexName, document1Id);
        final var snapshotName = "snapshot-delete-item";
        operations.takeSnapshot(snapshotName, indexName);

        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = srfs.extraSnapshotIndexData(SNAPSHOT_DIR.getAbsolutePath(), snapshotName, unpackedShardDataDir);

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, not(anyOf(containsString(document1Id), containsString(document1Body))));

        verifyNoMoreInteractions(client);
    }

    @Test
    @Disabled("https://opensearch.atlassian.net/browse/MIGRATIONS-1747")
    public void SingleSnapshot_SingleDocument_Then_UpdateDocument() throws Exception {
        // Setup
        final var indexName = "my-index-with-updated-item";
        final var document1Id = "doc1-going-to-be-updated";
        final var document1BodyOrginal = "{\"foo\":\"bar\"}";
        operations.createDocument(indexName, document1Id, document1BodyOrginal);
        final var document1BodyUpdated = "{\"actor\":\"troy mcclure\"}";
        operations.createDocument(indexName, document1Id, document1BodyUpdated);

        final var snapshotName = "snapshot-delete-item";
        operations.takeSnapshot(snapshotName, indexName);

        final var unpackedShardDataDir = Path.of(Files.createTempDirectory("unpacked-shard-data").toFile().getAbsolutePath());
        final var indices = srfs.extraSnapshotIndexData(SNAPSHOT_DIR.getAbsolutePath(), snapshotName, unpackedShardDataDir);

        final var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(any(), any())).thenReturn(Mono.empty());

        // Action
        srfs.updateTargetCluster(indices, unpackedShardDataDir, client);

        // Validation
        final var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(1)).sendBulkRequest(eq(indexName), bodyCaptor.capture());
        final var bulkRequestRaw = bodyCaptor.getValue();
        assertThat(bulkRequestRaw, allOf(containsString(document1Id), containsString(document1BodyUpdated), not(containsString(document1BodyOrginal))));

        verifyNoMoreInteractions(client);
    }
}
