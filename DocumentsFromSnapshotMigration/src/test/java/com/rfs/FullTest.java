package com.rfs;

import com.rfs.cms.ApacheHttpClient;
import com.rfs.cms.OpenSearchWorkCoordinator;
import com.rfs.cms.LeaseExpireTrigger;
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
import com.rfs.common.RestClient;
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
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.testcontainers.OpensearchContainer;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Tag("longTest")
@Slf4j
public class FullTest {
    final static long TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS = 3600;
    final static Pattern CAT_INDICES_INDEX_COUNT_PATTERN =
            Pattern.compile("(?:\\S+\\s+){2}(\\S+)\\s+(?:\\S+\\s+){3}(\\S+)");

    public static Stream<Arguments> makeArgs() {
        var sourceImageNames = List.of("migrations/elasticsearch_rfs_source");
        var targetImageNames = List.of("opensearchproject/opensearch:2.13.0", "opensearchproject/opensearch:1.3.0");
        var numWorkers = List.of(1, 3, 40);
        return sourceImageNames.stream()
                .flatMap(a->
                        targetImageNames.stream().flatMap(b->
                                numWorkers.stream().map(c->Arguments.of(a, b, c))));
    }

    @ParameterizedTest
    @MethodSource("makeArgs")
    public void test(String sourceImageName, String targetImageName, int numWorkers) throws Exception {
        try (ElasticsearchContainer esSourceContainer =
                     new ElasticsearchContainer(new ElasticsearchContainer.Version(sourceImageName,
                             "preloaded-ES_7_10"));
             OpensearchContainer<?> osTargetContainer =
                     new OpensearchContainer<>(targetImageName)) {
            esSourceContainer.start();
            osTargetContainer.start();

            final var SNAPSHOT_NAME = "test_snapshot";
            final List<String> INDEX_ALLOWLIST = List.of();
            CreateSnapshot.run(
                    c -> new FileSystemSnapshotCreator(SNAPSHOT_NAME, c, ElasticsearchContainer.CLUSTER_SNAPSHOT_DIR),
                    new OpenSearchClient(esSourceContainer.getUrl(), null),
                    false);
            var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_snapshot");
            try {
                esSourceContainer.copySnapshotData(tempDir.toString());

                var targetClient = new OpenSearchClient(osTargetContainer.getHttpHostAddress(), null);
                var sourceRepo = new FileSystemRepo(tempDir);
                migrateMetadata(sourceRepo, targetClient, SNAPSHOT_NAME, INDEX_ALLOWLIST);

                var workerFutures = new ArrayList<CompletableFuture<Void>>();
                var runCounter = new AtomicInteger();
                for (int i = 0; i < numWorkers; ++i) {
                    workerFutures.add(CompletableFuture.supplyAsync(() ->
                            migrateDocumentsSequentially(sourceRepo, SNAPSHOT_NAME, INDEX_ALLOWLIST,
                                    osTargetContainer.getHttpHostAddress(), runCounter)));
                }
                var thrownException = Assertions.assertThrows(ExecutionException.class, () ->
                        CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).get());
                var exceptionResults =
                        workerFutures.stream().map(cf -> {
                            try {
                                return cf.handle((v, t) ->
                                        Optional.ofNullable(t).map(Throwable::getCause).orElse(null))
                                        .get();
                            } catch (Exception e) {
                                throw Lombok.sneakyThrow(e);
                            }
                        }).filter(Objects::nonNull).collect(Collectors.toList());
                exceptionResults.forEach(e ->
                        log.atLevel(e instanceof RfsMigrateDocuments.NoWorkLeftException ? Level.INFO : Level.ERROR)
                                .setMessage(() -> "First exception for run").setCause(thrownException.getCause()).log());
                exceptionResults.forEach(e -> Assertions.assertInstanceOf(RfsMigrateDocuments.NoWorkLeftException.class, e));

                // for now, lets make sure that we got all of the
                Assertions.assertInstanceOf(RfsMigrateDocuments.NoWorkLeftException.class, thrownException.getCause(),
                        "expected at least one worker to notice that all work was completed.");
                checkClusterMigrationOnFinished(esSourceContainer, osTargetContainer);
                var totalCompletedWorkRuns = runCounter.get();
                Assertions.assertTrue(totalCompletedWorkRuns >= numWorkers,
                        "Expected to make more runs (" + totalCompletedWorkRuns + ") than the number of workers " +
                                "(" + numWorkers + ").  Increase the number of shards so that there is more work to do.");
            } finally {
                deleteTree(tempDir);
            }
        }
    }

    private void checkClusterMigrationOnFinished(ElasticsearchContainer esSourceContainer,
                                                 OpensearchContainer<?> osTargetContainer) {
        var targetClient = new RestClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(), null, null));
        var sourceMap = getIndexToCountMap(new RestClient(new ConnectionDetails(esSourceContainer.getUrl(),
                null, null)));
        var refreshResponse = targetClient.get("_refresh");
        Assertions.assertEquals(200, refreshResponse.code);
        var targetMap = getIndexToCountMap(targetClient);
        MatcherAssert.assertThat(targetMap, Matchers.equalTo(sourceMap));
    }

    private Map<String,Integer> getIndexToCountMap(RestClient client) {;
        var lines = Optional.ofNullable(client.get("_cat/indices"))
                .flatMap(r->Optional.ofNullable(r.body))
                .map(b->b.split("\n"))
                .orElse(new String[0]);
        return Arrays.stream(lines)
                .map(line -> {
                    var matcher = CAT_INDICES_INDEX_COUNT_PATTERN.matcher(line);
                    return !matcher.find() ? null :
                         new AbstractMap.SimpleEntry<>(matcher.group(1), matcher.group(2));
                })
                .filter(Objects::nonNull)
                .filter(kvp->!kvp.getKey().startsWith("."))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, kvp -> Integer.parseInt(kvp.getValue())));
    }

    @SneakyThrows
    private Void migrateDocumentsSequentially(FileSystemRepo sourceRepo,
                                              String snapshotName,
                                              List<String> indexAllowlist,
                                              String targetAddress,
                                              AtomicInteger runCounter) {
        for (int runNumber=0; ; ++runNumber) {
            try {
                var workResult = migrateDocumentsWithOneWorker(sourceRepo, snapshotName, indexAllowlist, targetAddress);
                if (workResult == DocumentsRunner.CompletionStatus.NOTHING_DONE) {
                    return null;
                } else {
                    runCounter.incrementAndGet();
                }
            } catch (RfsMigrateDocuments.NoWorkLeftException e) {
                log.info("No work at all was found.  " +
                        "Presuming that work was complete and that all worker processes should terminate");
                throw e;
            } catch (Exception e) {
                log.atError().setCause(e).setMessage(()->"Caught an exception, " +
                        "but just going to run again with this worker to simulate task/container recycling").log();
            }
        }
    }

    private static void migrateMetadata(SourceRepo sourceRepo, OpenSearchClient targetClient, String snapshotName, List<String> indexAllowlist) {
        SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
        GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
        GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(targetClient,
                List.of(), List.of(), List.of());
        Transformer transformer =
                TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
        IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(snapshotName, indexMetadataFactory, indexCreator, transformer, indexAllowlist).migrateIndices();
    }

    private static class FilteredLuceneDocumentsReader extends LuceneDocumentsReader {
        private final UnaryOperator<Document> docTransformer;

        public FilteredLuceneDocumentsReader(Path luceneFilesBasePath, UnaryOperator<Document> docTransformer) {
            super(luceneFilesBasePath);
            this.docTransformer = docTransformer;
        }

        @Override
        public Flux<Document> readDocuments() {
            return super.readDocuments().map(docTransformer::apply);
        }
    }

    static class LeasePastError extends Error { }

    @SneakyThrows
    private DocumentsRunner.CompletionStatus migrateDocumentsWithOneWorker(SourceRepo sourceRepo,
                                                                           String snapshotName,
                                                                           List<String> indexAllowlist,
                                                                           String targetAddress)
        throws RfsMigrateDocuments.NoWorkLeftException
    {
        var tempDir = Files.createTempDirectory("opensearchMigrationReindexFromSnapshot_test_lucene");
        var shouldThrow = new AtomicBoolean();
        try (var processManager = new LeaseExpireTrigger(workItemId->{
            log.atDebug().setMessage("Lease expired for " + workItemId + " making next document get throw").log();
            shouldThrow.set(true);
        })) {
            UnaryOperator<Document> terminatingDocumentFilter = d -> {
                if (shouldThrow.get()) {
                    throw new LeasePastError();
                }
                return d;
            };

            DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
            SnapshotShardUnpacker.Factory unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor,
                    tempDir, ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES);

            SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            ShardMetadata.Factory shardMetadataFactory = new ShardMetadataFactory_ES_7_10(repoDataProvider);

            return RfsMigrateDocuments.run(path -> new FilteredLuceneDocumentsReader(path, terminatingDocumentFilter),
                    new DocumentReindexer(new OpenSearchClient(targetAddress, null)),
                    new OpenSearchWorkCoordinator(
                            new ApacheHttpClient(new URI(targetAddress)),
//                            new ReactorHttpClient(new ConnectionDetails(osTargetContainer.getHttpHostAddress(),
//                                    null, null)),
                            TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS, UUID.randomUUID().toString()),
                    processManager,
                    indexMetadataFactory,
                    snapshotName,
                    indexAllowlist,
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
