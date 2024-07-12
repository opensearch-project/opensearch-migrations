package com.rfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;

import com.rfs.common.ClusterVersion;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.Lombok;

public class SourceTestBase {
    public static final String GENERATOR_BASE_IMAGE = "migrations/elasticsearch_client_test_console:latest";
    public static final String SOURCE_SERVER_ALIAS = "source";

    protected static Object[] makeParamsForBase(SearchClusterContainer.Version baseSourceImage) {
        return new Object[] {
            baseSourceImage,
            GENERATOR_BASE_IMAGE,
            new String[] { "/root/runTestBenchmarks.sh", "--endpoint", "http://" + SOURCE_SERVER_ALIAS + ":9200/" } };
    }

    protected static void migrateMetadata(
        SourceRepo sourceRepo,
        OpenSearchClient targetClient,
        String snapshotName,
        List<String> indexAllowlist,
        MetadataMigrationTestContext context
    ) {
        SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
        GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
        GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(
            targetClient,
            List.of(),
            List.of(),
            List.of(),
            context.createMetadataMigrationContext()
        );
        Transformer transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
        IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(
            snapshotName,
            indexMetadataFactory,
            indexCreator,
            transformer,
            indexAllowlist,
            context.createIndexContext()
        ).migrateIndices();
    }

    protected static void deleteTree(Path path) throws IOException {
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
