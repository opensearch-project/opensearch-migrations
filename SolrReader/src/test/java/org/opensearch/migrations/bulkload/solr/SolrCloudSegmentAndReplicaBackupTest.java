package org.opensearch.migrations.bulkload.solr;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves a SolrCloud backup holding more than one Lucene segment migrates completely.
 *
 * <p>Standalone already has this coverage
 * ({@code SolrBareS3StandaloneFlatRootDerivedNameDocumentMigrationTest}), but no SolrCloud test
 * asserted its backup's segment count, so the multi-segment Cloud path was unverified — the shape
 * <a href="https://github.com/opensearch-project/opensearch-migrations/issues/3116">#3116</a> was
 * reported against. Segment count is controlled by committing per batch; a force merge only bounds
 * it from above.
 *
 * <p>Scoped to Solr 6/7, whose Cloud BACKUP writes plain Lucene names under {@code snapshot.shardN/}
 * so segments can be counted directly. Solr 8/9 incremental backups store UUID filenames and would
 * need the shard metadata parsed to count segments.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
public class SolrCloudSegmentAndReplicaBackupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "segment_test";
    private static final int NUM_SHARDS = 2;
    private static final int DOC_COUNT = 40;

    @TempDir
    File tempDir;

    static Stream<Arguments> versionsAndBatches() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6, 4),
            Arguments.of(SolrClusterContainer.SOLR_6, 1),
            Arguments.of(SolrClusterContainer.SOLR_7, 4),
            Arguments.of(SolrClusterContainer.SOLR_7, 1)
        );
    }

    @ParameterizedTest(name = "{0} cloud backup, {1} indexing batch(es)")
    @MethodSource("versionsAndBatches")
    void multiSegmentCloudBackupMigratesAllDocuments(
        SolrClusterContainer.SolrVersion solrVersion, int batches
    ) throws Exception {
        var singleSegment = batches == 1;

        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            solr.start();
            target.start();

            createCloudCollection(solr, solrVersion);
            indexInBatches(solr, DOC_COUNT, batches);

            var schema = fetchSchema(solr, COLLECTION);
            var backupRoot = backupAndCopyToHost(solr, solrVersion);

            var shardDirs = listShardDirs(backupRoot);
            assertThat("backup should hold one directory per shard", shardDirs.size(), equalTo(NUM_SHARDS));
            for (var shardDir : shardDirs) {
                var segments = countSegments(shardDir);
                log.atInfo().setMessage("{} has {} segment(s)").addArgument(shardDir).addArgument(segments).log();
                if (singleSegment) {
                    assertThat("a single indexing batch should leave one segment in " + shardDir,
                        segments, equalTo(1));
                } else {
                    assertThat("multiple indexing batches should leave multiple segments in " + shardDir,
                        segments, greaterThanOrEqualTo(2));
                }
            }

            migrate(backupRoot, schema, solrVersion, target);
            verifyDocCount(target, COLLECTION, DOC_COUNT);
        }
    }

    // --- Solr setup ---

    private static void createCloudCollection(
        SolrClusterContainer solr, SolrClusterContainer.SolrVersion solrVersion
    ) throws Exception {
        // Solr 6 SolrCloud ships no auto-uploaded configset; Solr 7+ uploads _default itself.
        if (solrVersion.major() == 6) {
            var up = solr.execInContainer(
                "/opt/solr/bin/solr", "zk", "upconfig", "-n", "_default",
                "-d", "/opt/solr/server/solr/configsets/data_driven_schema_configs",
                "-z", "localhost:9983");
            if (up.getExitCode() != 0) {
                throw new IllegalStateException("Solr 6 upconfig failed: " + up.getStderr());
            }
        }

        var create = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + COLLECTION
                + "&numShards=" + NUM_SHARDS
                + "&replicationFactor=1"
                + "&maxShardsPerNode=" + NUM_SHARDS
                + (solrVersion.major() == 6 ? "&collection.configName=_default" : "")
                + "&wt=json");
        log.atInfo().setMessage("CREATE response: {}").addArgument(create.getStdout()).log();
        waitForCollectionActive(solr, NUM_SHARDS, 90);
    }

    /** One commit per batch: each flushes a segment, so batches>1 yields a multi-segment index. */
    private static void indexInBatches(SolrClusterContainer solr, int count, int batches) throws Exception {
        var perBatch = (int) Math.ceil((double) count / batches);
        for (int start = 1; start <= count; start += perBatch) {
            var end = Math.min(start + perBatch - 1, count);
            var sb = new StringBuilder("[");
            for (int i = start; i <= end; i++) {
                if (i > start) {
                    sb.append(",");
                }
                sb.append("{\"id\":\"doc").append(i).append("\",\"title_s\":\"Document ").append(i).append("\"}");
            }
            sb.append("]");
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
                "-d", sb.toString());
        }
    }

    private static void waitForCollectionActive(SolrClusterContainer solr, int expectedShards, int maxSeconds)
        throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            var status = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                    + COLLECTION + "&wt=json");
            var body = status.getStdout();
            int active = 0;
            int idx = 0;
            while ((idx = body.indexOf("\"state\":\"active\"", idx)) != -1) {
                active++;
                idx++;
            }
            if (active >= expectedShards) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Collection did not become active within " + maxSeconds + "s");
    }

    // --- Backup ---

    private Path backupAndCopyToHost(SolrClusterContainer solr, SolrClusterContainer.SolrVersion solrVersion)
        throws Exception {
        var probe = solr.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }
        var backupLocation = solrDataDir + "/backups";
        solr.execInContainer("mkdir", "-p", backupLocation);

        var backupName = "cloud_backup";
        var backup = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + backupName
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + "&wt=json");
        log.atInfo().setMessage("BACKUP response: {}").addArgument(backup.getStdout()).log();
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("BACKUP failed for " + solrVersion + ": " + backup.getStdout());
        }

        var localRoot = tempDir.toPath().resolve("cloud_backup_" + solrVersion.major() + "_"
            + System.nanoTime());
        copyDirectoryFromContainer(solr, backupLocation + "/" + backupName, localRoot);
        return localRoot;
    }

    private static void copyDirectoryFromContainer(
        SolrClusterContainer solr, String containerDir, Path localDir
    ) throws Exception {
        Files.createDirectories(localDir);
        var find = solr.execInContainer("find", containerDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var relative = line.substring(containerDir.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            var localFile = localDir.resolve(relative);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
        }
    }

    /** Solr 6/7 Cloud BACKUP writes one {@code snapshot.shardN/} directory per shard. */
    private static java.util.List<Path> listShardDirs(Path backupRoot) throws IOException {
        try (var walk = Files.walk(backupRoot, 3)) {
            return walk
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("snapshot.shard"))
                .sorted()
                .toList();
        }
    }

    private static int countSegments(Path shardDir) throws IOException {
        try (var files = Files.list(shardDir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".si")).count();
        }
    }

    // --- Migration ---

    private static void migrate(
        Path backupRoot, JsonNode schema,
        SolrClusterContainer.SolrVersion solrVersion, SearchClusterContainer target
    ) {
        var source = new SolrBackupSource(backupRoot, COLLECTION, schema, solrVersion.major());
        var sink = new OpenSearchDocumentSink(
            createOpenSearchClient(target), null, false, DocumentExceptionAllowlist.empty(), null
        );
        new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE).migrateAll().collectList().block();
    }

    private static JsonNode fetchSchema(SolrClusterContainer solr, String collection) throws Exception {
        var result = solr.execInContainer(
            "curl", "-s", "http://localhost:8983/solr/" + collection + "/schema?wt=json");
        return MAPPER.readTree(result.getStdout()).path("schema");
    }

    private static OpenSearchClient createOpenSearchClient(SearchClusterContainer cluster) {
        return new OpenSearchClientFactory(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        ).determineVersionAndCreate();
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext());
        restClient.get("_refresh", context.createUnboundRequestContext());
        assertEquals(
            expected,
            new SearchClusterRequests(context)
                .getMapOfIndexAndDocCount(restClient)
                .getOrDefault(indexName, 0),
            "all documents should reach the target index"
        );
    }
}
