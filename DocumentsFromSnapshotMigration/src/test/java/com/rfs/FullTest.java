package com.rfs;

import com.rfs.cms.AbstractedHttpClient;
import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.ProcessManager;
import com.rfs.cms.ReactorHttpClient;
import com.rfs.common.ClusterVersion;
import com.rfs.common.ConnectionDetails;
import com.rfs.common.DefaultSourceRepoAccessor;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.IndexMetadata;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.common.SourceRepo;
import com.rfs.framework.ElasticsearchContainer;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.ElasticsearchConstants_ES_7_10;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.ShardMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpensearchContainer;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

@Tag("longTest")
@Slf4j
public class FullTest {
    final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;
    final static ElasticsearchContainer esSourceContainer =
            new ElasticsearchContainer(new ElasticsearchContainer.Version("elasticsearch_rfs_source",
                    "preloaded-ES_7_10"));
    final static OpensearchContainer<?> osTargetContainer =
            new OpensearchContainer<>("opensearchproject/opensearch:2.13.0");

    @BeforeAll
    static void setupSourceContainer() {
        esSourceContainer.start();
        osTargetContainer.start();
    }

    @Test
    public void test() throws Exception {
        final var SNAPSHOT_NAME = "test_snapshot";
        CreateSnapshot.run(
                c -> new FileSystemSnapshotCreator(SNAPSHOT_NAME, c, ElasticsearchContainer.CLUSTER_SNAPSHOT_DIR),
                new OpenSearchClient(esSourceContainer.getUrl(), null));
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
        try {
            esSourceContainer.copySnapshotData(tempDir.toString());

            var targetClient = new OpenSearchClient(osTargetContainer.getHttpHostAddress(), null);
            var sourceRepo = new FileSystemRepo(tempDir);
            migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME);

            migrateDocumentsWithOneWorker(sourceRepo, SNAPSHOT_NAME);
        } finally {
            deleteTree(tempDir);
        }
    }

    private static void migrateMetadata(SourceRepo sourceRepo, OpenSearchClient targetClient, String snapshotName) {
        SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
        GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
        GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(targetClient,
                List.of(), List.of(), List.of());
        Transformer transformer =
                TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
        IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(snapshotName, indexMetadataFactory, indexCreator, transformer).migrateIndices();
    }

    private class FilteredLuceneDocumentsReader extends LuceneDocumentsReader {
        private final UnaryOperator<Document> docTransformer;

        public FilteredLuceneDocumentsReader(Path luceneFilesBasePath, UnaryOperator<Document> docTransformer) {
            super(luceneFilesBasePath);
            this.docTransformer = docTransformer;
        }

        @Override
        public Flux<Document> readDocuments(String indexName, int shard) {
            return super.readDocuments(indexName, shard).map(docTransformer::apply);
        }
    }

    static class LeasePastError extends Error { }

    private void migrateDocumentsWithOneWorker(SourceRepo sourceRepo, String snapshotName) throws Exception {
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");
        try {
            var shouldThrow = new AtomicBoolean();
            UnaryOperator<Document> terminatingDocumentFilter = d -> {
                if (shouldThrow.get()) {
                    throw new LeasePastError();
                }
                return d;
            };
            var processManager = new ProcessManager(workItemId->{
                log.atDebug().setMessage("Lease expired for " + workItemId + " making next document get throw").log();
                shouldThrow.set(true);
            });

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                    tempDir, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);

            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);

            RfsMigrateDocuments.run(path -> new FilteredLuceneDocumentsReader(path, terminatingDocumentFilter),
                    new DocumentReindexer(new OpenSearchClient(osTargetContainer.getHttpHostAddress(), null)),
                    new OpenSearchWorkCoordinator(
                            new ApacheHttpClient(new URI(osTargetContainer.getHttpHostAddress())),
//                            new ReactorHttpClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(),
//                                    null, null)),
                            TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS, UUID.randomUUID().toString()),
                    processManager,
                    indexMetadataFactory,
                    snapshotName,
                    shardMetadataFactory,
                    unpackerFactory,
                    16*1024*1024);
        } finally {
            deleteTree(tempDir);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
        }
    }
}
